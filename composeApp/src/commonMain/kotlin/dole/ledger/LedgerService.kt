package dole.ledger

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoStoreObserver
import com.ditto.kotlin.DittoSyncSubscription
import com.ditto.kotlin.DittoQueryResultItem
import dole.Constants
import dole.balance.BalanceCalculator
import dole.balance.BalanceResult
import dole.crypto.CryptoUtils
import dole.crypto.ProtocolSerializer
import dole.transaction.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LedgerService(private val ditto: Ditto) {

    private var activeSubscription: DittoSyncSubscription? = null
    private var activeObserver: DittoStoreObserver? = null

    private var currentObservedId: String? = null
    private var currentLastSeq: Int = 0
    private var currentLastReceivedGocs: MutableMap<String, Int> = mutableMapOf()
    private var currentLogConsumer: ((List<LedgerEntry>) -> Unit)? = null
    private var isFullHistoryMode = false

    private var activeQueryStr: String? = null

    suspend fun saveEntry(entry: LedgerEntry) = withContext(Dispatchers.IO) {
        val docId = "${entry.authorID}_${entry.seq}"

        val typeName = Constants.OperationType.fromCode(entry.type).name
        val prevHash = CryptoUtils.bytesToHex(entry.prevHash)
        val sig = CryptoUtils.bytesToHex(entry.signature)

        val pubKeyStr = entry.attachmentPublicKey?.let { CryptoUtils.bytesToHex(it) } ?: ""
        val certStr = entry.attachmentCertificate?.let { CryptoUtils.bytesToHex(it) } ?: ""

        val query = "INSERT INTO ${Constants.COLLECTION} DOCUMENTS ({ " +
                "'_id': '$docId', 'type': '$typeName', 'seq': ${entry.seq}, " +
                "'author': '${entry.authorID}', 'target': '${entry.targetID}', " +
                "'goc': ${entry.goc}, 'prevHash': '$prevHash', 'signature': '$sig', " +
                "'pubKey': '$pubKeyStr', 'cert': '$certStr'" +
                " }) ON ID CONFLICT DO UPDATE"

        try {
            ditto.store.execute(query)
        } catch (e: Exception) {
            println("CRITICAL: Failed to save log $docId - ${e.message}")
        }
    }

    fun observeRelevantLogs(
        myId: String,
        lastKnownSeq: Int,
        lastReceivedGocs: Map<String, Int>?,
        onLogsUpdated: (List<LedgerEntry>) -> Unit
    ) {
        this.currentObservedId = myId
        this.currentLastSeq = lastKnownSeq
        this.currentLastReceivedGocs = lastReceivedGocs?.toMutableMap() ?: mutableMapOf()
        this.currentLogConsumer = onLogsUpdated
        restartObserver()
    }

    fun setHistorySyncMode(loadFullHistory: Boolean) {
        if (this.isFullHistoryMode == loadFullHistory) return
        this.isFullHistoryMode = loadFullHistory
        restartObserver()
    }

    private fun restartObserver() {
        val myId = currentObservedId ?: return
        if (currentLogConsumer == null) return

        val whereString = if (isFullHistoryMode) {
            "author = '$myId' OR target = '$myId' OR seq = 0"
        } else {
            "(seq = 0) OR (author = '$myId' AND seq > $currentLastSeq) OR (target = '$myId')"
        }

        val subscriptionQuery = "SELECT * FROM ${Constants.COLLECTION} WHERE $whereString"
        val observerQuery = "SELECT * FROM ${Constants.COLLECTION} WHERE $whereString ORDER BY seq ASC"

        if (activeQueryStr == subscriptionQuery && activeObserver != null) return

        closeObserver()
        activeQueryStr = subscriptionQuery

        try {
            this.activeSubscription = ditto.sync.registerSubscription(subscriptionQuery)
            this.activeObserver = ditto.store.registerObserver(observerQuery) { result ->
                val entries = result.items.mapNotNull { mapToLedgerEntry(it) }.filter { isLogRelevant(it) }

                if (entries.isNotEmpty()) currentLogConsumer?.invoke(entries)
            }
        } catch (e: Exception) {
            println("Observer error: ${e.message}")
        }
    }

    private fun isLogRelevant(entry: LedgerEntry): Boolean {
        if (isFullHistoryMode) return true
        if (entry.seq == 0) return true
        if (entry.authorID == currentObservedId) return entry.seq > currentLastSeq
        if (entry.targetID == currentObservedId) {
            val storedGoc = currentLastReceivedGocs[entry.authorID] ?: -1
            return entry.goc > storedGoc
        }
        return false
    }

    private fun getMapValueStr(map: Map<*, *>, key: String): String {
        for ((k, v) in map) {
            val kStr = k.toString().replace("\"", "").replace("'", "")
            if (kStr == key || kStr == "Utf8String(value=$key)") {
                if (v == null) return ""
                var vStr = v.toString()
                if (vStr.contains("value=")) {
                    vStr = vStr.substringAfter("value=").substringBeforeLast(")")
                }
                return vStr.replace("\"", "").replace("'", "").trim()
            }
        }
        return ""
    }

    private fun extractIntSafely(map: Map<*, *>, key: String): Int {
        val str = getMapValueStr(map, key)
        return str.toDoubleOrNull()?.toInt() ?: 0
    }

    private fun mapToLedgerEntry(item: DittoQueryResultItem): LedgerEntry? {
        try {
            val map = item.value as? Map<*, *> ?: return null

            val seq = extractIntSafely(map, "seq")
            val goc = extractIntSafely(map, "goc")

            val typeStrRaw = getMapValueStr(map, "type")
            val type: Byte = try {
                Constants.OperationType.valueOf(typeStrRaw.uppercase()).code
            } catch (_: Exception) {
                if (seq == 0) Constants.OperationType.GENESIS.code else Constants.OperationType.MINT.code
            }

            val author = getMapValueStr(map, "author")
            val targetStr = getMapValueStr(map, "target")
            val target = targetStr.ifEmpty { author }

            var prevHashHex = getMapValueStr(map, "prevHash")
            if (prevHashHex.isEmpty() && seq == 0) {
                prevHashHex = "".padStart(Constants.HASH_SIZE * 2, '0')
            }

            val sigHex = getMapValueStr(map, "signature")
            val pubKeyStr = getMapValueStr(map, "pubKey")
            val certStr = getMapValueStr(map, "cert")

            val payload = ProtocolSerializer.buildLogPayloadFromHex(
                seq = seq,
                prevHashHex = prevHashHex,
                type = type,
                authorHex = author,
                targetHex = target,
                goc = goc
            )

            val sigBytes = CryptoUtils.hexToBytes(sigHex)
            val pubKeyBytes = if (pubKeyStr.isNotEmpty() && pubKeyStr != "null") CryptoUtils.hexToBytes(pubKeyStr) else null
            val certBytes = if (certStr.isNotEmpty() && certStr != "null") CryptoUtils.hexToBytes(certStr) else null

            return LedgerEntry.createFromNetwork(
                payload = payload,
                sig = sigBytes,
                seq = seq,
                goc = goc,
                type = type,
                pubKey = pubKeyBytes,
                cert = certBytes
            )
        } catch (e: Exception) {
            println("Map error (Skipping invalid TX): ${e.message}")
            return null
        }
    }

    fun stopObserving() {
        closeObserver()
        this.currentObservedId = null
        this.currentLogConsumer = null
        this.currentLastReceivedGocs.clear()
        this.isFullHistoryMode = false
        this.activeQueryStr = null
    }

    private fun closeObserver() {
        try {
            activeSubscription?.close()
            activeObserver?.close()
            activeSubscription = null
            activeObserver = null
        } catch (_: Exception) { }
    }

    fun calculateBalance(transactions: List<Transaction>?, currentId: String?): BalanceResult {
        return BalanceCalculator.calculate(transactions, currentId)
    }
}