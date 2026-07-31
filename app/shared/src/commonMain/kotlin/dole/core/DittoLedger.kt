package dole.core

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoAuthenticationProvider
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoConnectionType
import com.ditto.kotlin.DittoPeer
import com.ditto.kotlin.DittoStoreObserver
import com.ditto.kotlin.DittoSyncSubscription
import dole.Constants
import dole.data.models.BurnTransaction
import dole.data.models.GenesisTransaction
import dole.data.models.LedgerJson
import dole.data.models.MintTransaction
import dole.data.models.SendTransaction
import dole.data.models.Transaction
import dole.viewmodel.PeerConnection
import dole.viewmodel.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal object DittoLedger {
    private const val TRANSACTIONS = "transactions"
    private const val BENCHMARK_TIMEOUT_MS = 120_000L
    private val WORKLOAD_MILESTONES = listOf(100, 250, 500, 750, 1000)

    private data class StoredTransaction(
        val id: String,
        val txType: String,
        val author: String,
        val publicKey: String,
        val certificate: String,
        val targetId: String,
        val goc: Long,
        val seq: Long,
        val timestamp: Long,
        val signature: String
    )

    private data class VerificationCacheEntry(
        val stored: StoredTransaction,
        val publicKey: String?,
        val transaction: NativeLedgerTransaction?
    )

    private data class ScaleEvent(
        val stage: String,
        val responder: String,
        val milestone: Int,
        val verifyMicros: Long
    )

    private data class OutgoingScale(
        val runId: String,
        val origin: String,
        val events: Channel<ScaleEvent>
    )

    private data class IncomingScale(
        val origin: String,
        val transactionIds: Set<String>,
        var started: Boolean = false,
        var verifyMicros: Long = 0L,
        val acknowledged: MutableSet<Int> = mutableSetOf()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val createMutex = Mutex()
    private val writeMutex = Mutex()

    private var ditto: Ditto? = null
    private var storagePath = ""
    private var transactionSubscription: DittoSyncSubscription? = null
    private var transactionObserver: DittoStoreObserver? = null
    private var latencyObserver: DittoStoreObserver? = null

    private var stateListener: ((Long, String) -> Unit)? = null
    private var publicKeyId = ""
    private var publicKey = ""
    private var knownTransactions = emptyList<NativeLedgerTransaction>()
    private var workloadTransactions = emptyList<NativeLedgerTransaction>()
    private val verificationCache = mutableMapOf<String, VerificationCacheEntry>()
    private val scaleTransactionIds = mutableSetOf<String>()

    private var bleEnabled = true
    private var localEnabled = true
    private var internetEnabled = true
    private var syncing = false
    private var sessionId = ""
    private var peers = emptyList<PeerConnection>()
    private var status = TransportStatus(false, false, false)

    private var benchmarkMode = false
    private var pendingProbeId: String? = null
    private var pendingProbeMark: TimeMark? = null
    private var pendingInternalMicros = 0L
    private var storageReport: String? = null
    private var latencyReport: String? = null
    private var scaleRunning = false
    private var outgoingScale: OutgoingScale? = null
    private val incomingScales = mutableMapOf<String, IncomingScale>()
    private val finishedIncomingScales = mutableSetOf<String>()

    fun initialize(
        listener: (Long, String) -> Unit,
        path: String,
        keyId: String,
        fullKey: String
    ) {
        storagePath = path
        stateListener = listener
        publicKeyId = keyId
        publicKey = fullKey
        scope.launch {
            ensureDitto(path)
            publishState(knownTransactions + workloadTransactions)
        }
    }

    fun detachLedger() {
        stateListener = null
        publicKeyId = ""
        publicKey = ""
    }

    suspend fun start(path: String) {
        val current = ensureDitto(path)
        applyTransportConfig(current)
        if (!current.sync.isActive) current.sync.start()
        syncing = current.sync.isActive
        updateStatus()
    }

    fun stop() {
        ditto?.sync?.stop()
        syncing = false
        updateStatus()
    }

    suspend fun reset(path: String): Boolean = runCatching {
        val current = ensureDitto(path)
        current.store.execute("EVICT FROM $TRANSACTIONS WHERE true")
        knownTransactions = emptyList()
        workloadTransactions = emptyList()
        verificationCache.clear()
        scaleTransactionIds.clear()
        publishState(emptyList())
        true
    }.getOrDefault(false)

    suspend fun genesis(sigHex: String, certHex: String): Boolean {
        val keyId = publicKeyId
        val key = publicKey
        if (keyId.isEmpty() || key.isEmpty()) return false
        if (knownTransactions.any { it.author.equals(keyId, true) && it.txType == "G" }) return true
        val transaction = NativeCore.prepareGenesisTransaction(keyId, key, sigHex, certHex) ?: return false
        return insertTransaction(transaction)
    }

    suspend fun record(
        txType: String,
        target: String,
        goc: Long,
        seq: Long,
        sigHex: String
    ): Boolean {
        val keyId = publicKeyId
        if (keyId.isEmpty()) return false
        if (knownTransactions.any { it.author.equals(keyId, true) && it.seq == seq }) return false
        val key = publicKey.ifEmpty {
            knownTransactions.firstOrNull { it.author.equals(keyId, true) && it.txType == "G" }?.publicKey.orEmpty()
        }
        if (key.isEmpty()) return false
        val transaction = NativeCore.prepareLedgerTransaction(
            key,
            txType,
            target,
            goc,
            seq,
            sigHex
        ) ?: return false
        return insertTransaction(transaction)
    }

    fun setBleEnabled(enabled: Boolean) {
        bleEnabled = enabled
        ditto?.let(::applyTransportConfig)
        updateStatus()
    }

    fun setLocalEnabled(enabled: Boolean) {
        localEnabled = enabled
        ditto?.let(::applyTransportConfig)
        updateStatus()
    }

    fun setInternetEnabled(enabled: Boolean) {
        internetEnabled = enabled
        ditto?.let(::applyTransportConfig)
        updateStatus()
    }

    fun refreshPermissions() {
        ditto?.refreshPermissions()
    }

    fun localSessionId(): String = sessionId
    fun connectedPeers(): List<PeerConnection> = peers
    fun transportStatus(): TransportStatus = status

    fun benchSetMode(enabled: Boolean) {
        benchmarkMode = enabled
        if (!enabled) {
            pendingProbeId = null
            pendingProbeMark = null
            outgoingScale?.events?.close()
            outgoingScale = null
            scaleRunning = false
            incomingScales.clear()
            finishedIncomingScales.clear()
            latencyObserver?.close()
            latencyObserver = null
        } else {
            scope.launch {
                val current = ensureDitto(storagePath)
                ensureBenchmarkResources(current)
            }
        }
    }

    fun benchModeEnabled(): Boolean = benchmarkMode

    fun benchHasWorkload(): Boolean = (knownTransactions + workloadTransactions)
        .any { it.txType == "G" }

    fun benchGenerateWorkload(): Int {
        val mark = TimeSource.Monotonic.markNow()
        val workload = NativeCore.benchGenerateTransactions()
        val totalMicros = mark.elapsedNow().inWholeMicroseconds
        workloadTransactions = workload
        publishState(knownTransactions + workloadTransactions)
        logBenchmark("dole::bench workload tx,total_ms")
        logBenchmark("dole::bench workload ${workload.size},${formatMillis(totalMicros)}")
        return workload.size
    }

    suspend fun benchRunScale(path: String): Boolean {
        if (scaleRunning) {
            logBenchmark("dole::bench scale status=busy")
            return false
        }
        scaleRunning = true
        return try {
            val current = ensureDitto(path)
            ensureBenchmarkResources(current)
            val local = localPeerId(current)
            if (local.isEmpty()) {
                logBenchmark("dole::bench scale status=no_local_peer")
                return false
            }
            logBenchmark("dole::bench scale tx,sync_ms,verify_ms,total_ms")
            for (target in WORKLOAD_MILESTONES) {
                val workload = NativeCore.benchGenerateTransactions().take(target)
                if (workload.size != target || !runScaleSample(current, local, workload)) return false
            }
            logBenchmark("dole::bench scale status=complete")
            true
        } finally {
            scaleRunning = false
        }
    }

    private suspend fun runScaleSample(
        current: Ditto,
        local: String,
        workload: List<NativeLedgerTransaction>
    ): Boolean {
        val target = workload.size
        val runId = "$local:${Random.nextLong()}:${Random.nextLong()}"
        val events = Channel<ScaleEvent>(Channel.UNLIMITED)
        outgoingScale = OutgoingScale(runId, local, events)
        scaleTransactionIds += workload.map { it.id }
        return try {
            insertBenchmarkControl(
                current,
                mapOf(
                    "_id" to "run:$runId",
                    "kind" to "scaleRun",
                    "runId" to runId,
                    "origin" to local,
                    "transactionIds" to workload.joinToString(",") { it.id }
                )
            )
            val ready = awaitScaleEvent(events, "ready")
            if (ready == null) {
                logBenchmark("dole::bench scale status=ready_timeout tx=$target")
                return false
            }
            insertBenchmarkControl(
                current,
                mapOf(
                    "_id" to "start:$runId:${ready.responder}",
                    "kind" to "scaleStart",
                    "runId" to runId,
                    "origin" to local,
                    "target" to ready.responder
                )
            )
            val started = awaitScaleEvent(events, "started", responder = ready.responder)
            if (started == null) {
                logBenchmark("dole::bench scale status=start_timeout tx=$target")
                return false
            }
            val senderMark = TimeSource.Monotonic.markNow()
            var inserted = 0
            while (inserted < target) {
                if (insertScaleTransaction(current, workload[inserted], runId)) inserted++ else break
            }
            if (inserted != target) {
                logBenchmark("dole::bench scale status=insert_failed tx=$target inserted=$inserted")
                return false
            }
            val ack = awaitScaleEvent(
                events,
                stage = "verified",
                responder = ready.responder,
                milestone = target
            )
            if (ack == null) {
                logBenchmark("dole::bench scale status=verify_timeout tx=$target")
                return false
            }
            val totalMicros = senderMark.elapsedNow().inWholeMicroseconds
            val syncMicros = (totalMicros - ack.verifyMicros).coerceAtLeast(0L)
            logBenchmark(
                "dole::bench scale $target,${formatMillis(syncMicros)}," +
                    "${formatMillis(ack.verifyMicros)},${formatMillis(totalMicros)}"
            )
            true
        } finally {
            outgoingScale = null
            events.close()
            runCatching {
                current.store.execute(
                    "DELETE FROM $TRANSACTIONS WHERE benchmarkRun = :runId",
                    mapOf("runId" to runId)
                )
            }
        }
    }

    suspend fun benchRunStore(path: String): Int {
        storageReport = null
        val scratchPath = "${path.trimEnd('/', '\\')}_benchmark_store"
        if (!resetBenchmarkDirectory(scratchPath)) return 0
        val config = DittoConfig(
            databaseId = Constants.DITTO_DATABASE_ID,
            connect = DittoConfig.Connect.Server(Constants.DITTO_SERVER_URL),
            persistenceDirectory = scratchPath
        )
        val scratch = runCatching { createPlatformDitto(config) }.getOrElse {
            deleteBenchmarkDirectory(scratchPath)
            return 0
        }
        val baseline = scratch.diskUsage.item.sizeInBytes
        val workload = NativeCore.benchGenerateTransactions()
        var inserted = 0
        logBenchmark("dole::bench storage tx,bytes")
        return try {
            for (transaction in workload) {
                if (!insertTransaction(scratch, transaction, TRANSACTIONS)) continue
                inserted++
                val bytes = (scratch.diskUsage.item.sizeInBytes - baseline).coerceAtLeast(0L)
                logBenchmark("dole::bench storage $inserted,$bytes")
            }
            val documents = scratch.store.execute("SELECT * FROM $TRANSACTIONS") { result ->
                result.items.size
            }
            val finalBytes = scratch.diskUsage.item.sizeInBytes
            val deltaBytes = (finalBytes - baseline).coerceAtLeast(0L)
            logBenchmark("dole::bench storage status=complete tx=$documents bytes=$deltaBytes")
            storageReport = "Local storage: $documents transactions, $deltaBytes bytes."
            documents
        } finally {
            scratch.close()
            deleteBenchmarkDirectory(scratchPath)
        }
    }

    fun benchStorageReport(): String? = storageReport

    suspend fun benchRunLatency(path: String): Boolean {
        if (pendingProbeId != null) return false
        val transaction = (knownTransactions + workloadTransactions)
            .firstOrNull { it.txType == "G" } ?: return false
        val current = ensureDitto(path)
        ensureBenchmarkResources(current)
        val local = localPeerId(current)
        if (local.isEmpty()) return false
        val probeId = "$local:${Random.nextLong()}:${Random.nextLong()}"
        val mark = TimeSource.Monotonic.markNow()
        val document = transactionFields(transaction) + mapOf(
            "_id" to probeId,
            "transactionId" to transaction.id,
            "kind" to "probe",
            "probeId" to probeId,
            "origin" to local
        )
        val internalMicros = mark.elapsedNow().inWholeMicroseconds
        return if (insertBenchmarkControl(current, document)) {
            pendingProbeId = probeId
            pendingProbeMark = mark
            pendingInternalMicros = internalMicros
            true
        } else {
            false
        }
    }

    fun benchLatencyReport(): String? {
        val report = latencyReport
        latencyReport = null
        return report
    }

    private suspend fun ensureDitto(path: String): Ditto = createMutex.withLock {
        ditto?.let { return@withLock it }
        require(path.isNotBlank())
        storagePath = path
        val config = DittoConfig(
            databaseId = Constants.DITTO_DATABASE_ID,
            connect = DittoConfig.Connect.Server(Constants.DITTO_SERVER_URL),
            persistenceDirectory = path
        )
        val created = createPlatformDitto(config)
        created.auth?.expirationHandler = { current, _ ->
            current.auth?.login(
                token = Constants.DITTO_DEVELOPMENT_TOKEN,
                provider = DittoAuthenticationProvider.development()
            )
        }
        applyTransportConfig(created)
        transactionSubscription = created.sync.registerSubscription("SELECT * FROM $TRANSACTIONS")
        transactionObserver = created.store.registerObserver(
            "SELECT * FROM $TRANSACTIONS ORDER BY author ASC, seq ASC"
        ) { result ->
            val stored = result.items.mapNotNull { item ->
                val value = item.value
                readStoredTransaction(
                    id = runCatching { value["_id"].string }.getOrDefault(""),
                    stringValue = { key -> value[key].string },
                    longValue = { key -> value[key].long }
                )
            }
            val verificationMark = TimeSource.Monotonic.markNow()
            val validated = validateTransactions(stored, verificationCache)
            val verificationMicros = verificationMark.elapsedNow().inWholeMicroseconds
            handleScaleProgress(created, validated, verificationMicros)
            knownTransactions = validated.filterNot { it.id in scaleTransactionIds }
            publishState(knownTransactions + workloadTransactions)
        }
        scope.launch {
            created.presence.observe().collect { graph ->
                sessionId = graph.localPeer.peerKey.take(12)
                peers = graph.remotePeers.map(::peerConnection)
                updateStatus(graph.localPeer.isConnectedToDittoServer)
            }
        }
        ditto = created
        created
    }

    private fun applyTransportConfig(current: Ditto) {
        runCatching {
            current.updateTransportConfig { config ->
                config.peerToPeer.bluetoothLe.enabled = bleEnabled
                config.peerToPeer.lan.enabled = localEnabled
                config.peerToPeer.wifiAware.enabled = false
                config.connect.websocketUrls.clear()
                if (internetEnabled) {
                    config.connect.websocketUrls.add(Constants.DITTO_WEBSOCKET_URL)
                }
            }
        }
    }

    private suspend fun insertTransaction(transaction: NativeLedgerTransaction): Boolean {
        val current = ensureDitto(storagePath)
        return writeMutex.withLock { insertTransaction(current, transaction, TRANSACTIONS) }
    }

    private suspend fun insertTransaction(
        current: Ditto,
        transaction: NativeLedgerTransaction,
        collection: String
    ): Boolean = runCatching {
        current.store.execute(
            "INSERT INTO $collection DOCUMENTS (:doc) ON ID CONFLICT DO NOTHING",
            mapOf(
                "doc" to (transactionFields(transaction) + mapOf("_id" to transaction.id))
            )
        )
        true
    }.getOrDefault(false)

    private suspend fun insertScaleTransaction(
        current: Ditto,
        transaction: NativeLedgerTransaction,
        runId: String
    ): Boolean = runCatching {
        current.store.execute(
            "INSERT INTO $TRANSACTIONS DOCUMENTS (:doc) ON ID CONFLICT DO NOTHING",
            mapOf(
                "doc" to (
                    transactionFields(transaction) + mapOf(
                        "_id" to transaction.id,
                        "benchmarkRun" to runId
                    )
                )
            )
        )
        true
    }.getOrDefault(false)

    private fun validateTransactions(
        input: List<StoredTransaction>,
        cache: MutableMap<String, VerificationCacheEntry>
    ): List<NativeLedgerTransaction> {
        val ordered = input.distinctBy { it.id }.sortedWith(compareBy({ it.author }, { it.seq }))
        val activeIds = ordered.mapTo(mutableSetOf()) { it.id }
        cache.keys.toList().filterNot(activeIds::contains).forEach(cache::remove)
        val genesisTransactions = ordered
            .filter { it.txType == "G" }
            .mapNotNull { stored ->
                val cached = cache[stored.id]
                val transaction = if (cached?.stored == stored && cached.publicKey == stored.publicKey) {
                    cached.transaction
                } else {
                    validateGenesisTransaction(stored).also {
                        cache[stored.id] = VerificationCacheEntry(stored, stored.publicKey, it)
                    }
                }
                transaction
            }
        val publicKeys = genesisTransactions.associate { it.author.uppercase() to it.publicKey }
        val transactions = ordered
            .filter { it.txType != "G" }
            .mapNotNull { stored ->
                if (stored.publicKey.isNotEmpty() || stored.certificate.isNotEmpty()) return@mapNotNull null
                val publicKey = publicKeys[stored.author.uppercase()] ?: return@mapNotNull null
                val cached = cache[stored.id]
                if (cached?.stored == stored && cached.publicKey == publicKey) {
                    cached.transaction
                } else {
                    NativeCore.prepareLedgerTransaction(
                        publicKey = publicKey,
                        txType = stored.txType,
                        targetId = stored.targetId,
                        goc = stored.goc,
                        seq = stored.seq,
                        sigHex = stored.signature,
                        timestamp = stored.timestamp
                    )?.takeIf { it.matches(stored) }.also {
                        cache[stored.id] = VerificationCacheEntry(stored, publicKey, it)
                    }
                }
            }
        return (genesisTransactions + transactions).sortedWith(compareBy({ it.author }, { it.seq }))
    }

    private fun validateGenesisTransaction(stored: StoredTransaction): NativeLedgerTransaction? {
        if (
            stored.txType != "G" ||
            stored.seq != 0L ||
            stored.goc != 0L ||
            stored.targetId.isNotEmpty() ||
            stored.publicKey.isEmpty() ||
            stored.certificate.isEmpty()
        ) {
            return null
        }
        return NativeCore.prepareGenesisTransaction(
            publicKeyId = stored.author,
            publicKey = stored.publicKey,
            sigHex = stored.signature,
            certHex = stored.certificate,
            timestamp = stored.timestamp
        )?.takeIf { it.matches(stored) }
    }

    private fun publishState(transactions: List<NativeLedgerTransaction>) {
        val listener = stateListener ?: return
        val myId = publicKeyId
        if (publicKey.isEmpty() && myId.isNotEmpty()) {
            publicKey = transactions.firstOrNull { it.txType == "G" && it.author.equals(myId, true) }?.publicKey.orEmpty()
        }
        val tracker = mutableMapOf<String, Long>()
        val visible = mutableListOf<Transaction>()
        var balance = 0L
        for (transaction in transactions) {
            val key = if (transaction.txType == "S") {
                "${transaction.author}_S_${transaction.targetId}"
            } else {
                "${transaction.author}_${transaction.txType}"
            }
            val previous = tracker[key] ?: 0L
            val delta = transaction.goc - previous
            tracker[key] = transaction.goc
            val own = transaction.author.equals(myId, true)
            val incoming = transaction.txType == "S" && transaction.targetId.equals(myId, true)
            if (own) {
                balance += when (transaction.txType) {
                    "M" -> delta
                    "B", "S" -> -delta
                    else -> 0L
                }
            } else if (incoming) {
                balance += delta
            }
            if (myId.isNotEmpty() && !own && !incoming && transaction.txType != "G") continue
            visible += transaction.toDisplayTransaction(delta)
        }
        listener(balance, LedgerJson.encodeToString(visible.sortedByDescending { it.timestamp }))
    }

    private fun NativeLedgerTransaction.toDisplayTransaction(delta: Long): Transaction = when (txType) {
        "G" -> GenesisTransaction(id, author, timestamp, seq, signature, publicKey, certificate)
        "M" -> MintTransaction(id, author, timestamp, seq, signature, delta)
        "B" -> BurnTransaction(id, author, timestamp, seq, signature, delta)
        "S" -> SendTransaction(id, author, timestamp, seq, signature, targetId, delta)
        else -> error("Unsupported transaction type")
    }

    private fun peerConnection(peer: DittoPeer): PeerConnection {
        val types = peer.connections.map { it.connectionType }.toSet()
        return PeerConnection(
            sessionId = peer.peerKey.take(12).ifEmpty { peer.deviceName },
            ble = DittoConnectionType.Bluetooth in types,
            local = DittoConnectionType.AccessPoint in types,
            internet = peer.isConnectedToDittoServer || DittoConnectionType.WebSocket in types
        )
    }

    private fun updateStatus(serverConnected: Boolean = ditto?.presence?.graph?.localPeer?.isConnectedToDittoServer == true) {
        status = TransportStatus(
            ble = syncing && bleEnabled,
            local = syncing && localEnabled,
            internet = syncing && internetEnabled && serverConnected
        )
    }

    private fun ensureBenchmarkResources(current: Ditto) {
        if (latencyObserver != null) return
        latencyObserver = current.store.registerObserver(
            "SELECT * FROM $TRANSACTIONS WHERE kind IS NOT MISSING"
        ) { result ->
            for (item in result.items) {
                val value = item.value
                val kind = runCatching { value["kind"].string }.getOrDefault("")
                val probeId = runCatching { value["probeId"].string }.getOrDefault("")
                val origin = runCatching { value["origin"].string }.getOrDefault("")
                val local = localPeerId(current)
                when (kind) {
                    "probe" -> if (origin.isNotEmpty() && origin != local) {
                        val peerMark = TimeSource.Monotonic.markNow()
                        val stored = readStoredTransaction(
                            id = runCatching { value["transactionId"].string }.getOrDefault(""),
                            stringValue = { key -> value[key].string },
                            longValue = { key -> value[key].long }
                        )
                        stored?.let(::validateGenesisTransaction)
                        val peerMicros = peerMark.elapsedNow().inWholeMicroseconds
                        insertBenchmarkControl(
                            current,
                            mapOf(
                                "_id" to "ack:$probeId:$local",
                                "kind" to "ack",
                                "probeId" to probeId,
                                "origin" to origin,
                                "peerMicros" to peerMicros
                            )
                        )
                    }
                    "ack" -> if (probeId == pendingProbeId && origin == local) {
                        val peerMicros = runCatching { value["peerMicros"].long }.getOrDefault(0L)
                        val totalMicros = pendingProbeMark?.elapsedNow()?.inWholeMicroseconds ?: 0L
                        latencyReport = "Latency: internal ${formatMillis(pendingInternalMicros)} ms, peer ${formatMillis(peerMicros)} ms, total ${formatMillis(totalMicros)} ms"
                        logBenchmark(
                            "dole::bench latency ${formatMillis(pendingInternalMicros)}," +
                                "${formatMillis(peerMicros)},${formatMillis(totalMicros)}"
                        )
                        pendingProbeId = null
                        pendingProbeMark = null
                        current.store.execute(
                            "DELETE FROM $TRANSACTIONS WHERE probeId = :probeId",
                            mapOf("probeId" to probeId)
                        )
                    }
                    "scaleRun" -> {
                        val runId = runCatching { value["runId"].string }.getOrDefault("")
                        val ids = runCatching { value["transactionIds"].string }.getOrDefault("")
                            .split(',')
                            .filter(String::isNotEmpty)
                            .toSet()
                        if (
                            runId.isNotEmpty() &&
                            origin.isNotEmpty() &&
                            origin != local &&
                            ids.size in WORKLOAD_MILESTONES &&
                            runId !in finishedIncomingScales &&
                            runId !in incomingScales
                        ) {
                            incomingScales.clear()
                            scaleTransactionIds += ids
                            incomingScales[runId] = IncomingScale(origin, ids)
                            insertBenchmarkControl(
                                current,
                                mapOf(
                                    "_id" to "ready:$runId:$local",
                                    "kind" to "scaleReady",
                                    "runId" to runId,
                                    "origin" to origin,
                                    "responder" to local
                                )
                            )
                        }
                    }
                    "scaleReady", "scaleStarted", "scaleAck" -> {
                        val runId = runCatching { value["runId"].string }.getOrDefault("")
                        val responder = runCatching { value["responder"].string }.getOrDefault("")
                        val outgoing = outgoingScale
                        if (outgoing != null && runId == outgoing.runId && origin == outgoing.origin) {
                            val stage = when (kind) {
                                "scaleReady" -> "ready"
                                "scaleStarted" -> "started"
                                else -> "verified"
                            }
                            outgoing.events.trySend(
                                ScaleEvent(
                                    stage = stage,
                                    responder = responder,
                                    milestone = runCatching { value["milestone"].long.toInt() }.getOrDefault(0),
                                    verifyMicros = runCatching { value["verifyMicros"].long }.getOrDefault(0L)
                                )
                            )
                        }
                    }
                    "scaleStart" -> {
                        val runId = runCatching { value["runId"].string }.getOrDefault("")
                        val target = runCatching { value["target"].string }.getOrDefault("")
                        val incoming = incomingScales[runId]
                        if (incoming != null && incoming.origin == origin && target == local && !incoming.started) {
                            incoming.started = true
                            incoming.verifyMicros = 0L
                            incoming.acknowledged.clear()
                            insertBenchmarkControl(
                                current,
                                mapOf(
                                    "_id" to "started:$runId:$local",
                                    "kind" to "scaleStarted",
                                    "runId" to runId,
                                    "origin" to origin,
                                    "responder" to local
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleScaleProgress(
        current: Ditto,
        transactions: List<NativeLedgerTransaction>,
        verificationMicros: Long
    ) {
        val validIds = transactions.mapTo(mutableSetOf()) { it.id }
        val local = localPeerId(current)
        val completed = mutableListOf<String>()
        for ((runId, incoming) in incomingScales.toList()) {
            if (!incoming.started) continue
            incoming.verifyMicros += verificationMicros
            val verified = incoming.transactionIds.count(validIds::contains)
            val target = incoming.transactionIds.size
            if (verified < target || !incoming.acknowledged.add(target)) continue
            insertBenchmarkControl(
                current,
                mapOf(
                    "_id" to "scale-ack:$runId:$local:$target",
                    "kind" to "scaleAck",
                    "runId" to runId,
                    "origin" to incoming.origin,
                    "responder" to local,
                    "milestone" to target,
                    "verifyMicros" to incoming.verifyMicros
                )
            )
            completed += runId
        }
        for (runId in completed) {
            incomingScales.remove(runId)
            finishedIncomingScales += runId
        }
    }

    private suspend fun insertBenchmarkControl(current: Ditto, document: Map<String, Any>): Boolean = runCatching {
        val fields = mapOf<String, Any>(
            "type" to "GENESIS",
            "author" to "",
            "publicKey" to "",
            "certificate" to "",
            "targetId" to "",
            "goc" to 0L,
            "seq" to 0L,
            "timestamp" to 0L,
            "signature" to ""
        ) + document + (document["runId"]?.let { mapOf("benchmarkRun" to it) } ?: emptyMap())
        current.store.execute(
            "INSERT INTO $TRANSACTIONS DOCUMENTS (:doc) ON ID CONFLICT DO NOTHING",
            mapOf("doc" to fields)
        )
        true
    }.getOrDefault(false)

    private suspend fun awaitScaleEvent(
        events: Channel<ScaleEvent>,
        stage: String,
        responder: String? = null,
        milestone: Int? = null
    ): ScaleEvent? = withTimeoutOrNull(BENCHMARK_TIMEOUT_MS.milliseconds) {
        var matched: ScaleEvent? = null
        while (matched == null) {
            val event = events.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
            if (
                event.stage == stage &&
                (responder == null || event.responder == responder) &&
                (milestone == null || event.milestone == milestone)
            ) {
                matched = event
            }
        }
        matched
    }

    private fun localPeerId(current: Ditto): String = sessionId.ifEmpty { current.presence.graph.localPeer.peerKey.take(12) }

    private fun formatMillis(micros: Long): String {
        val whole = micros / 1000
        val fraction = (micros % 1000).toString().padStart(3, '0')
        return "$whole.$fraction"
    }

    private fun transactionFields(transaction: NativeLedgerTransaction): Map<String, Any> = mapOf(
        "type" to documentType(transaction.txType),
        "author" to transaction.author,
        "publicKey" to transaction.publicKey,
        "certificate" to transaction.certificate,
        "targetId" to transaction.targetId,
        "goc" to transaction.goc,
        "seq" to transaction.seq,
        "timestamp" to transaction.timestamp,
        "signature" to transaction.signature
    )

    private fun readStoredTransaction(
        id: String,
        stringValue: (String) -> String,
        longValue: (String) -> Long
    ): StoredTransaction? = runCatching {
        StoredTransaction(
            id = id,
            txType = transactionType(stringValue("type")) ?: error("Invalid transaction type"),
            author = stringValue("author"),
            publicKey = stringValue("publicKey"),
            certificate = stringValue("certificate"),
            targetId = stringValue("targetId"),
            goc = longValue("goc"),
            seq = longValue("seq"),
            timestamp = longValue("timestamp"),
            signature = stringValue("signature")
        )
    }.getOrNull()

    private fun NativeLedgerTransaction.matches(stored: StoredTransaction): Boolean =
        id == stored.id &&
            txType == stored.txType &&
            author.equals(stored.author, true) &&
            publicKey.equals(stored.publicKey, true) &&
            certificate.equals(stored.certificate, true) &&
            targetId.equals(stored.targetId, true) &&
            goc == stored.goc &&
            seq == stored.seq &&
            timestamp == stored.timestamp &&
            signature.equals(stored.signature, true)

    private fun documentType(txType: String): String = when (txType) {
        "G" -> "GENESIS"
        "M" -> "MINT"
        "B" -> "BURN"
        "S" -> "SEND"
        else -> error("Invalid transaction type")
    }

    private fun transactionType(type: String): String? = when (type) {
        "GENESIS" -> "G"
        "MINT" -> "M"
        "BURN" -> "B"
        "SEND" -> "S"
        else -> null
    }
}
