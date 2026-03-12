package dole.wallet

import dole.gui.PendingAction
import dole.ledger.Ledger
import dole.ledger.Ledger.AccountState
import dole.ledger.Ledger.LedgerState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CombinedUserData(
    var name: String? = null,
    var pinHash: String? = null,
    var pendingActions: List<PendingAction>? = null,
    var lastReceivedTx: Map<String, Int>? = null,
    var lastSentTx: Map<String, Int>? = null,
    var savedBalance: Int? = null,
    var lastHash: String? = null,
    var publicKey: String? = null,
    var lastSeq: Int = 0,
    var totalMinted: Int? = null,
    var totalBurned: Int = 0
)

class SettingsService(private val storage: WalletStorage) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false }

    var accounts: MutableList<StoredAccount> = mutableListOf()

    private val pendingActions = mutableMapOf<String, MutableList<PendingAction>>()
    private val lastReceivedTx = mutableMapOf<String, MutableMap<String, Int>>()
    private val lastSentTx = mutableMapOf<String, MutableMap<String, Int>>()
    private val savedBalances = mutableMapOf<String, Int>()

    private val knownMinted = mutableMapOf<String, Int>()
    private val knownBurned = mutableMapOf<String, Int>()

    private var initialLedgerState: LedgerState? = LedgerState()
    private var activeLedger: Ledger? = null

    init {
        loadData()
    }

    @Synchronized
    fun attachLedger(ledger: Ledger) {
        this.activeLedger = ledger
        initialLedgerState?.let { ledger.initialize(it) }
    }

    fun isMinter(userId: String): Boolean = knownMinted.containsKey(userId)

    @Synchronized
    fun setMinterStatus(userId: String, isMinter: Boolean) {
        if (isMinter) knownMinted.putIfAbsent(userId, 0) else knownMinted.remove(userId)
        persist()
    }

    @Synchronized
    fun getInitialAccountState(userId: String): AccountState {
        val state = initialLedgerState?.states?.get(userId) ?: AccountState()
        state.totalMinted = maxOf(state.totalMinted, knownMinted[userId] ?: 0)
        state.totalBurned = maxOf(state.totalBurned, knownBurned[userId] ?: 0)
        return state
    }

    @Synchronized
    fun updateAccountState(userId: String, mintGoc: Int, burnGoc: Int) {
        if (mintGoc > 0 || knownMinted.containsKey(userId)) {
            knownMinted[userId] = maxOf(knownMinted[userId] ?: 0, mintGoc)
        }
        knownBurned[userId] = maxOf(knownBurned[userId] ?: 0, burnGoc)
        persist()
    }

    fun getLastKnownSeq(userId: String): Int {
        activeLedger?.exportState()?.states?.get(userId)?.let { return it.lastSeq }
        return initialLedgerState?.states?.get(userId)?.lastSeq ?: 0
    }

    @Synchronized
    fun saveBalance(userId: String, balance: Int) {
        savedBalances[userId] = balance
        persist()
    }

    fun getLastBalance(userId: String): Int = savedBalances[userId] ?: 0

    @Synchronized
    fun getKnownPeers(myId: String): List<String> {
        val peers = mutableSetOf<String>()
        lastReceivedTx[myId]?.keys?.let { peers.addAll(it) }
        lastSentTx[myId]?.keys?.let { peers.addAll(it) }
        accounts.filter { it.id != myId }.forEach { peers.add(it.id) }
        return peers.toList()
    }

    @Synchronized
    fun saveAccount(id: String, name: String, pinHash: String) {
        accounts.removeAll { it.id == id }
        accounts.add(StoredAccount(id, name, pinHash))
        persist()
    }

    @Synchronized
    fun removeAccount(id: String) {
        accounts.removeAll { it.id == id }
        pendingActions.remove(id)
        lastReceivedTx.remove(id)
        lastSentTx.remove(id)
        savedBalances.remove(id)
        knownMinted.remove(id)
        knownBurned.remove(id)
        activeLedger?.removeAccount(id)
        persist()
    }

    @Synchronized
    fun savePendingActions(userId: String, actions: List<PendingAction>?) {
        if (actions.isNullOrEmpty()) pendingActions.remove(userId) else pendingActions[userId] = actions.toMutableList()
        persist()
    }

    fun loadPendingActions(userId: String): List<PendingAction> = pendingActions[userId] ?: emptyList()

    fun getLastReceivedGocs(userId: String): MutableMap<String, Int> = lastReceivedTx[userId]?.toMutableMap() ?: mutableMapOf()
    fun getLastSentGocs(userId: String): MutableMap<String, Int> = lastSentTx[userId]?.toMutableMap() ?: mutableMapOf()

    @Synchronized
    fun importLastReceivedGocs(myId: String, updates: Map<String, Int>?) {
        if (updates.isNullOrEmpty()) return
        val map = lastReceivedTx.getOrPut(myId) { mutableMapOf() }
        updates.forEach { (peerId, newGoc) -> map[peerId] = maxOf(map[peerId] ?: 0, newGoc) }
        persist()
    }

    @Synchronized
    fun importLastSentGocs(myId: String, updates: Map<String, Int>?) {
        if (updates.isNullOrEmpty()) return
        val map = lastSentTx.getOrPut(myId) { mutableMapOf() }
        updates.forEach { (peerId, newGoc) -> map[peerId] = maxOf(map[peerId] ?: 0, newGoc) }
        persist()
    }

    @Synchronized
    fun persist() {
        val exportMap = mutableMapOf<String, CombinedUserData>()
        val myIds = accounts.map { it.id }.toSet()

        accounts.forEach { acc ->
            val data = exportMap.getOrPut(acc.id) { CombinedUserData() }
            data.name = acc.name
            data.pinHash = acc.pinHash
        }

        pendingActions.filter { it.value.isNotEmpty() }.forEach { (id, actions) ->
            exportMap.getOrPut(id) { CombinedUserData() }.pendingActions = actions.toList()
        }

        lastReceivedTx.filter { it.value.isNotEmpty() }.forEach { (id, map) ->
            exportMap.getOrPut(id) { CombinedUserData() }.lastReceivedTx = map.toMap()
        }

        lastSentTx.filter { it.value.isNotEmpty() }.forEach { (id, map) ->
            exportMap.getOrPut(id) { CombinedUserData() }.lastSentTx = map.toMap()
        }

        savedBalances.forEach { (id, bal) ->
            exportMap.getOrPut(id) { CombinedUserData() }.savedBalance = bal
        }

        val ls = activeLedger?.exportState() ?: initialLedgerState
        ls?.let { state ->
            state.keys.filterKeys { myIds.contains(it) }.forEach { (id, key) ->
                exportMap.getOrPut(id) { CombinedUserData() }.publicKey = key
            }
            state.hashes.filterKeys { myIds.contains(it) }.forEach { (id, hash) ->
                val data = exportMap.getOrPut(id) { CombinedUserData() }
                data.lastHash = hash
                val asState = state.states[id]
                data.lastSeq = asState?.lastSeq ?: 0
                val ledgerMint = asState?.totalMinted ?: 0
                val ledgerBurn = asState?.totalBurned ?: 0

                data.totalMinted = if (knownMinted.containsKey(id)) maxOf(ledgerMint, knownMinted[id] ?: 0) else null
                data.totalBurned = maxOf(ledgerBurn, knownBurned[id] ?: 0)
            }
        }

        try {
            val jsonString = json.encodeToString(exportMap)
            storage.writeData(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun loadData() {
        try {
            val jsonString = storage.readData()
            if (jsonString.isNullOrBlank()) return

            val dataMap: Map<String, CombinedUserData> = json.decodeFromString(jsonString)
            initialLedgerState = LedgerState()

            dataMap.forEach { (id, data) ->
                data.name?.let { accounts.add(StoredAccount(id, it, data.pinHash ?: "")) }
                data.pendingActions?.let { pendingActions[id] = it.toMutableList() }
                data.lastReceivedTx?.let { lastReceivedTx[id] = it.toMutableMap() }
                data.lastSentTx?.let { lastSentTx[id] = it.toMutableMap() }
                data.savedBalance?.let { savedBalances[id] = it }
                data.totalMinted?.let { knownMinted[id] = it }
                if (data.totalBurned > 0) knownBurned[id] = data.totalBurned

                if (data.lastHash != null || data.publicKey != null) {
                    val asState = AccountState()
                    asState.lastSeq = data.lastSeq
                    asState.totalMinted = data.totalMinted ?: 0
                    asState.totalBurned = data.totalBurned
                    initialLedgerState!!.states[id] = asState
                    data.lastHash?.let { initialLedgerState!!.hashes[id] = it }
                    data.publicKey?.let { initialLedgerState!!.keys[id] = it }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}