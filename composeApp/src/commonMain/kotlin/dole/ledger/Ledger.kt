package dole.ledger

import dole.Constants
import dole.crypto.CryptoUtils
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class Ledger {
    private val rootCA: PublicKey? = try {
        CryptoUtils.decodePublicKey(Constants.ROOT_CA_BYTES)
    } catch (_: Exception) {
        null
    }

    private val keyStore = ConcurrentHashMap<String, PublicKey>()
    private val hashCache = ConcurrentHashMap<String, ByteArray>()
    private val accountStates = ConcurrentHashMap<String, AccountState>()

    @Synchronized
    fun initialize(state: LedgerState?) {
        if (state == null) return
        this.accountStates.putAll(state.states)
        state.hashes.forEach { (k, v) ->
            this.hashCache[k] = CryptoUtils.hexToBytes(v)
        }
        state.keys.forEach { (k, v) ->
            CryptoUtils.decodePublicKey(CryptoUtils.hexToBytes(v))?.let { pubKey ->
                this.keyStore[k] = pubKey
            }
        }
    }

    @Synchronized
    fun exportState(): LedgerState {
        val state = LedgerState()
        state.states.putAll(this.accountStates)
        this.hashCache.forEach { (k, v) ->
            state.hashes[k] = CryptoUtils.bytesToHex(v)
        }
        this.keyStore.forEach { (k, v) ->
            state.keys[k] = CryptoUtils.bytesToHex(v.encoded)
        }
        return state
    }

    @Synchronized
    fun processLogBatch(batch: List<LedgerEntry>, activeUserId: String): List<LedgerEntry> {
        val validLogs = mutableListOf<LedgerEntry>()
        val sortedBatch = batch.sortedWith(compareBy<LedgerEntry> { it.authorID }.thenBy { it.seq })

        for (log in sortedBatch) {
            try {
                if (validate(log, activeUserId)) {
                    applyLog(log)
                    validLogs.add(log)
                }
            } catch (e: Exception) {
                println("LOG REJECTED (${log.seq}): ${e.message}")
            }
        }
        return validLogs
    }

    private fun validate(log: LedgerEntry, activeUserId: String): Boolean {
        val author = log.authorID
        val state = accountStates[author]

        if (state != null && log.seq <= state.lastSeq) return false

        val expectedHash = hashCache[author] ?: Constants.ZERO_HASH
        val isMe = author == activeUserId

        if (!log.prevHash.contentEquals(expectedHash)) {
            if (isMe) println("WARN: Local Hash mismatch")
        }

        if (log.type == Constants.OperationType.GENESIS.code || log.type == Constants.OperationType.MINT.code || log.type == Constants.OperationType.BURN.code) {
            val targetId = log.targetID
            val zeroIdHex = CryptoUtils.bytesToHex(Constants.ZERO_ID)

            if (targetId != zeroIdHex) throw Exception("Invalid target for operation (must be zero)")
        }

        var key = keyStore[author]
        if (key == null) {
            if (log.type != Constants.OperationType.GENESIS.code) throw Exception("Unknown author (missing Genesis)")
            if (!log.verifyCertificate(rootCA)) throw Exception("Invalid Certificate")

            log.attachmentPublicKey?.let { pubKeyBytes ->
                key = CryptoUtils.decodePublicKey(pubKeyBytes)
                key?.let { keyStore[author] = it }
            }
        }

        if (!log.verifyLogSig(key)) throw Exception("Invalid Log Signature")

        return true
    }

    private fun applyLog(log: LedgerEntry) {
        val author = log.authorID
        hashCache[author] = log.hash

        val state = accountStates.getOrPut(author) { AccountState() }
        state.lastSeq = log.seq

        val op = Constants.OperationType.fromCode(log.type)
        if (op == Constants.OperationType.MINT) {
            state.totalMinted = max(state.totalMinted, log.goc)
        } else if (op == Constants.OperationType.BURN) {
            state.totalBurned = max(state.totalBurned, log.goc)
        }
    }

    fun getPublicKey(id: String): PublicKey? {
        return keyStore[id]
    }

    class LedgerState {
        var states = mutableMapOf<String, AccountState>()
        var hashes = mutableMapOf<String, String>()
        var keys = mutableMapOf<String, String>()
    }

    class AccountState {
        var lastSeq: Int = 0
        var totalMinted: Int = 0
        var totalBurned: Int = 0
    }

    @Synchronized
    fun removeAccount(id: String) {
        accountStates.remove(id)
        hashCache.remove(id)
        keyStore.remove(id)
    }
}