package dole.wallet

import dole.Constants
import dole.balance.TransactionDelta
import dole.card.SmartCard
import dole.crypto.CryptoUtils
import dole.crypto.ProtocolSerializer
import dole.ledger.Ledger
import dole.ledger.LedgerEntry
import dole.ledger.LedgerService
import dole.transaction.BurnTransaction
import dole.transaction.MintTransaction
import dole.transaction.SendTransaction
import dole.transaction.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Suppress("DuplicatedCode")
class WalletService(
    private val card: SmartCard,
    private val userPin: CharArray,
    private val settingsService: SettingsService,
    private val ledgerService: LedgerService,
    private val ledger: Ledger
) {
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private val pinBytes: ByteArray = ProtocolSerializer.validateAndConvertPin(userPin)
    private val ownID: String
    private var myPublicKeyBytes: ByteArray

    private val knownGenesisLogs = mutableMapOf<String, LedgerEntry>()
    private val processedTxIds = mutableSetOf<String>()
    private val sessionVerifiedNewIds = mutableSetOf<String>()
    private val recoveredTxIds = mutableSetOf<String>()

    private var cachedLogs = listOf<LedgerEntry>()
    private var isFullHistoryMode = false
    private var isClosed = false

    private var snapshotLastSeq = 0
    private val snapshotReceivedGocs = mutableMapOf<String, Int>()
    private val snapshotSentGocs = mutableMapOf<String, Int>()

    private val startupReceivedGocs = mutableMapOf<String, Int>()
    private val startupSentGocs = mutableMapOf<String, Int>()
    private var startupMintGoc = 0
    private var startupBurnGoc = 0
    private var snapshotMintGoc = 0
    private var snapshotBurnGoc = 0

    private var onStateUpdate: ((WalletState) -> Unit)? = null
    private var pollingJob: Job? = null

    init {
        if (!card.isConnected) card.connect()
        if (!card.verifyPin(pinBytes)) throw Exception("Start failed: Invalid PIN")

        myPublicKeyBytes = card.publicKey ?: throw Exception("Failed to read public key from card")
        ownID = CryptoUtils.getPersonIdAsHex(myPublicKeyBytes)
    }

    data class WalletState(
        val balance: Int,
        val confirmedBalance: Int,
        val fullHistory: List<TransactionDelta>,
        val recentlySynced: List<TransactionDelta>,
        val unsyncedIncoming: List<Transaction>,
        val knownPeers: Set<String>,
        val unsyncedPendingSum: Long
    )

    fun setOnStateUpdateListener(listener: (WalletState) -> Unit) {
        this.onStateUpdate = listener
    }

    fun setSearchMode(enabled: Boolean) {
        if (enabled) {
            this.isFullHistoryMode = true
            ledgerService.setHistorySyncMode(true)
            onLedgerUpdate(this.cachedLogs)
        } else {
            this.isFullHistoryMode = false
            ledgerService.setHistorySyncMode(false)
        }
    }

    fun start() {
        this.snapshotLastSeq = settingsService.getLastKnownSeq(ownID)
        this.snapshotReceivedGocs.putAll(settingsService.getLastReceivedGocs(ownID))
        this.snapshotSentGocs.putAll(settingsService.getLastSentGocs(ownID))

        val asState = settingsService.getInitialAccountState(ownID)
        this.snapshotMintGoc = asState.totalMinted
        this.snapshotBurnGoc = asState.totalBurned

        this.startupReceivedGocs.putAll(this.snapshotReceivedGocs)
        this.startupSentGocs.putAll(this.snapshotSentGocs)
        this.startupMintGoc = this.snapshotMintGoc
        this.startupBurnGoc = this.snapshotBurnGoc

        val hasLocalHistory = (snapshotMintGoc > 0 || snapshotBurnGoc > 0 || snapshotSentGocs.isNotEmpty() || snapshotReceivedGocs.isNotEmpty())
        val needAutoResync = (snapshotLastSeq > 0 && !hasLocalHistory)
        val effectiveSeq = if (needAutoResync) 0 else snapshotLastSeq

        isClosed = false
        ledgerService.setHistorySyncMode(isFullHistoryMode)
        ledgerService.observeRelevantLogs(ownID, effectiveSeq, snapshotReceivedGocs) { logs ->
            onLedgerUpdate(logs)
        }

        startPolling()
    }

    private fun startPolling() {
        pollingJob = serviceScope.launch {
            while (isActive && !isClosed) {
                try {
                    onLedgerUpdate(cachedLogs)
                } catch (e: Exception) {
                    println("Polling error: ${e.message}")
                }
                delay(2000)
            }
        }
    }

    @Synchronized
    private fun onLedgerUpdate(allLogs: List<LedgerEntry>) {
        if (isClosed) return

        this.cachedLogs = allLogs
        for (log in allLogs) {
            if (log.seq == 0) knownGenesisLogs[log.authorID] = log
        }

        ledger.processLogBatch(allLogs, ownID)

        val allTransactions = allLogs.mapNotNull { mapLogToTransaction(it) }

        val isRebuildingSnapshots = isFullHistoryMode ||
                (snapshotLastSeq > 0 && snapshotMintGoc == 0 && snapshotBurnGoc == 0 && snapshotSentGocs.isEmpty() && snapshotReceivedGocs.isEmpty())

        val newOutgoingGocs = mutableMapOf<String, Int>()
        val allIncomingForBalance = mutableMapOf<String, Int>()
        val persistableIncomingGocs = mutableMapOf<String, Int>()

        var mintBurnUpdated = false

        for (tx in allTransactions) {
            val isOutgoing = tx.author == ownID

            when (tx) {
                is MintTransaction -> {
                    if (isOutgoing && tx.goc > snapshotMintGoc) {
                        snapshotMintGoc = tx.goc.toInt()
                        mintBurnUpdated = true
                    }
                }
                is BurnTransaction -> {
                    if (isOutgoing && tx.goc > snapshotBurnGoc) {
                        snapshotBurnGoc = tx.goc.toInt()
                        mintBurnUpdated = true
                    }
                }
                is SendTransaction -> {
                    if (isOutgoing) {
                        val currentSent = snapshotSentGocs[tx.target] ?: 0
                        snapshotSentGocs[tx.target] = maxOf(currentSent, tx.goc.toInt())
                        newOutgoingGocs[tx.target] = tx.goc.toInt()
                    } else if (tx.target == ownID) {
                        val currentInc = allIncomingForBalance[tx.author] ?: 0
                        allIncomingForBalance[tx.author] = maxOf(currentInc, tx.goc.toInt())

                        if (isRebuildingSnapshots || sessionVerifiedNewIds.contains(tx.id) || recoveredTxIds.contains(tx.id)) {
                            val currentPers = persistableIncomingGocs[tx.author] ?: 0
                            persistableIncomingGocs[tx.author] = maxOf(currentPers, tx.goc.toInt())
                        }
                    }
                }
                else -> {}
            }
        }

        var totalSent = 0L
        for (value in snapshotSentGocs.values) {
            totalSent += value.toLong()
        }

        val displayBalance = calculateInternalBalance(totalSent, allIncomingForBalance)
        val confirmedBalance = calculateInternalBalance(totalSent, persistableIncomingGocs)

        if (newOutgoingGocs.isNotEmpty()) settingsService.importLastSentGocs(ownID, newOutgoingGocs)
        if (mintBurnUpdated) settingsService.updateAccountState(ownID, snapshotMintGoc, snapshotBurnGoc)

        if (persistableIncomingGocs.isNotEmpty()) {
            settingsService.importLastReceivedGocs(ownID, persistableIncomingGocs)
            for ((k, v) in persistableIncomingGocs) {
                val cur = snapshotReceivedGocs[k] ?: 0
                snapshotReceivedGocs[k] = maxOf(cur, v)
            }
        }

        settingsService.saveBalance(ownID, displayBalance)

        val listResult = ledgerService.calculateBalance(allTransactions, ownID)

        val unsyncedTxIds = mutableSetOf<String>()
        val trulyUnsyncedTxs = mutableListOf<Transaction>()
        var unsyncedSum = 0L

        for (log in allLogs) {
            if (log.type == Constants.OperationType.SEND.code && ownID == log.targetID) {
                val lastKnown = snapshotReceivedGocs[log.authorID] ?: -1
                if (log.goc > lastKnown) {
                    val txId = CryptoUtils.bytesToHex(log.hash)
                    unsyncedTxIds.add(txId)
                    val tx = mapLogToTransaction(log)
                    if (tx != null) {
                        trulyUnsyncedTxs.add(tx)
                        val delta = listResult.items.find { it.tx.id == txId }?.delta ?: 0L
                        unsyncedSum += delta
                    }
                }
            }
        }

        val fullHistory = mutableListOf<TransactionDelta>()

        for (item in listResult.items) {
            var finalItem = item
            when (val tx = item.tx) {
                is MintTransaction -> {
                    if (tx.author == ownID && item.delta == tx.goc && startupMintGoc > 0) {
                        val corrected = item.delta - startupMintGoc
                        if (corrected > 0 && tx.goc > startupMintGoc) {
                            finalItem = TransactionDelta(item.tx, corrected)
                        }
                    }
                }
                is BurnTransaction -> {
                    if (tx.author == ownID && item.delta == tx.goc && startupBurnGoc > 0) {
                        val corrected = item.delta - startupBurnGoc
                        if (corrected > 0 && tx.goc > startupBurnGoc) {
                            finalItem = TransactionDelta(item.tx, corrected)
                        }
                    }
                }
                is SendTransaction -> {
                    if (tx.author != ownID) {
                        if (item.delta == tx.goc) {
                            val lastKnown = startupReceivedGocs[tx.author] ?: 0
                            if (lastKnown > 0 && tx.goc > lastKnown) {
                                val corrected = item.delta - lastKnown
                                finalItem = TransactionDelta(item.tx, corrected)
                            }
                        }
                    } else {
                        if (item.delta == tx.goc) {
                            val lastKnown = startupSentGocs[tx.target] ?: 0
                            if (lastKnown > 0 && tx.goc > lastKnown) {
                                val corrected = item.delta - lastKnown
                                finalItem = TransactionDelta(item.tx, corrected)
                            }
                        }
                    }
                }
            }

            if (isFullHistoryMode || !unsyncedTxIds.contains(finalItem.tx.id)) {
                fullHistory.add(finalItem)
            }
        }

        val newItems = mutableListOf<TransactionDelta>()
        synchronized(processedTxIds) {
            for (item in fullHistory) {
                val txId = item.tx.id
                if (sessionVerifiedNewIds.contains(txId)) {
                    if (!processedTxIds.contains(txId)) {
                        processedTxIds.add(txId)
                        newItems.add(item)
                    }
                }
            }
        }

        val knownPeers = cachedLogs.map { it.authorID }.toSet()

        onStateUpdate?.invoke(WalletState(
            balance = displayBalance,
            confirmedBalance = confirmedBalance,
            fullHistory = fullHistory,
            recentlySynced = newItems,
            unsyncedIncoming = trulyUnsyncedTxs,
            knownPeers = knownPeers,
            unsyncedPendingSum = unsyncedSum
        ))
    }

    private fun calculateInternalBalance(totalSent: Long, additionalIncoming: Map<String, Int>): Int {
        val combinedMap = HashMap<String, Int>()
        combinedMap.putAll(snapshotReceivedGocs)
        for ((k, v) in additionalIncoming) {
            combinedMap[k] = maxOf(combinedMap[k] ?: 0, v)
        }
        var totalRecv = 0L
        for (value in combinedMap.values) {
            totalRecv += value.toLong()
        }
        return (snapshotMintGoc - snapshotBurnGoc - totalSent + totalRecv).toInt()
    }

    @Synchronized
    fun syncIncomingTransactions(txs: List<Transaction>) {
        if (isClosed) return
        if (txs.isEmpty()) return

        val entriesToSync = mutableListOf<LedgerEntry>()
        for (tx in txs) {
            cachedLogs.find { CryptoUtils.bytesToHex(it.hash) == tx.id }?.let { entriesToSync.add(it) }
        }
        if (entriesToSync.isEmpty()) return

        internalSyncCard(entriesToSync, txs)
        onLedgerUpdate(this.cachedLogs)
    }

    private fun internalSyncCard(entriesToSync: List<LedgerEntry>, originalTxs: List<Transaction>) {
        for (log in entriesToSync) {
            val txId = CryptoUtils.bytesToHex(log.hash)
            if (processedTxIds.contains(txId)) continue

            try {
                applyLogToCard(log)
                sessionVerifiedNewIds.add(txId)

                val tx = originalTxs.find { it.id == txId }
                if (tx is SendTransaction && tx.target == ownID) {
                    val current = snapshotReceivedGocs[tx.author] ?: 0
                    if (tx.goc > current) {
                        snapshotReceivedGocs[tx.author] = tx.goc.toInt()
                        settingsService.importLastReceivedGocs(ownID, mapOf(tx.author to tx.goc.toInt()))
                    }
                }
            } catch (e: Exception) {
                if (isCardAlreadySyncedError(e)) {
                    recoveredTxIds.add(txId)
                } else {
                    println("Manual-Sync failed for $txId: ${e.message}")
                }
            }
            try { Thread.sleep(20) } catch (_: InterruptedException) {}
        }
    }

    private fun isCardAlreadySyncedError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("6985") || msg.contains("Conditions not satisfied") || msg.contains("6A80")
    }

    private fun applyLogToCard(log: LedgerEntry) {
        val authorId = log.authorID

        try {
            ensurePeerRegistered(authorId)
        } catch (_: Exception) {}

        val genesis = knownGenesisLogs[authorId] ?: cachedLogs.find { it.authorID == authorId && it.type == Constants.OP_GENESIS }
        val genesisPubKey = genesis?.attachmentPublicKey

        val senderRawKey: ByteArray = genesisPubKey
            ?: ledger.getPublicKey(authorId)?.encoded
            ?: throw IllegalStateException("Key missing for $authorId")

        val logSig = log.signature ?: throw Exception("Missing Signature")

        val payload = ProtocolSerializer.buildReceivePayload(
            pinBytes,
            senderRawKey,
            logSig,
            log.payloadBytes
        )

        try {
            if (!card.isConnected) card.connect()
            card.verifyPin(pinBytes)
        } catch (e: Exception) {
            throw Exception("Could not verify PIN for Receive: ${e.message}")
        }

        card.processReceive(payload)
    }

    private fun ensurePeerRegistered(peerID: String) {
        val genesis = knownGenesisLogs[peerID] ?: cachedLogs.find { it.authorID == peerID && it.type == Constants.OP_GENESIS }

        val cert = genesis?.attachmentCertificate
        val pubKey = genesis?.attachmentPublicKey ?: ledger.getPublicKey(peerID)?.encoded

        if (cert != null && pubKey != null) {
            try {
                if (!card.isConnected) card.connect()
                card.verifyPin(pinBytes)
                card.addPeer(ProtocolSerializer.buildAddPeerPayload(cert, pubKey))
            } catch (_: Exception) {}
        } else {
            if (ledger.getPublicKey(peerID) == null) {
                throw Exception("Cannot register peer: Genesis cert missing for $peerID")
            }
        }
    }

    @Synchronized
    fun initGenesisTx() {
        if (!card.isConnected) card.connect()
        card.verifyPin(pinBytes)

        if (myPublicKeyBytes.isEmpty()) myPublicKeyBytes = card.publicKey ?: throw Exception("Missing public key")
        val cert = card.certificate ?: throw Exception("Missing cert")
        val response = card.processGenesis()

        val entry = LedgerEntry.fromCardResponse(response)
        entry.setAttachments(cert, myPublicKeyBytes)

        serviceScope.launch { ledgerService.saveEntry(entry) }
        knownGenesisLogs[ownID] = entry
        ledger.processLogBatch(listOf(entry), ownID)
    }

    @Synchronized
    fun mint(amount: Int): Transaction? {
        val payload = ProtocolSerializer.buildMintBurnPayload(pinBytes, amount)
        return executeTx(payload) { p -> card.processMint(p) }
    }

    @Synchronized
    fun burn(amount: Int): Transaction? {
        val payload = ProtocolSerializer.buildMintBurnPayload(pinBytes, amount)
        return executeTx(payload) { p -> card.processBurn(p) }
    }

    @Synchronized
    fun send(targetIdHex: String, amount: Int): Transaction? {
        try {
            ensurePeerRegistered(targetIdHex)
        } catch (_: Exception) {}

        val payload = ProtocolSerializer.buildSendPayload(pinBytes, targetIdHex, amount)
        return executeTx(payload) { p -> card.processSend(p) }
    }

    private fun executeTx(payload: ByteArray, op: (ByteArray) -> ByteArray): Transaction? {
        if (!card.isConnected) card.connect()
        card.verifyPin(pinBytes)

        val response = op(payload)
        val entry = LedgerEntry.fromCardResponse(response)
        val txId = CryptoUtils.bytesToHex(entry.hash)

        sessionVerifiedNewIds.add(txId)
        serviceScope.launch { ledgerService.saveEntry(entry) }
        return mapLogToTransaction(entry)
    }

    private fun mapLogToTransaction(entry: LedgerEntry): Transaction? {
        val type = Constants.OperationType.fromCode(entry.type)
        val id = CryptoUtils.bytesToHex(entry.hash)
        val seq = entry.seq
        val auth = entry.authorID
        val goc = entry.goc.toLong()

        return when (type) {
            Constants.OperationType.GENESIS -> null
            Constants.OperationType.MINT -> MintTransaction(id, seq, auth, goc)
            Constants.OperationType.BURN -> BurnTransaction(id, seq, auth, goc)
            Constants.OperationType.SEND -> SendTransaction(id, seq, auth, entry.targetID, goc)
            else -> null
        }
    }

    fun close() {
        isClosed = true
        pollingJob?.cancel()
    }
}