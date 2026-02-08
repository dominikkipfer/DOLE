package dole.gui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dole.Constants
import dole.card.SmartCard
import dole.crypto.CryptoUtils
import dole.ledger.Ledger
import dole.ledger.LedgerEntry
import dole.ledger.LedgerService
import dole.transaction.BurnTransaction
import dole.transaction.MintTransaction
import dole.transaction.SendTransaction
import dole.transaction.Transaction
import dole.wallet.SettingsService
import dole.wallet.StoredAccount
import dole.wallet.WalletService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.*

enum class AppScreenState { HOME, LOGIN, SETUP, DASHBOARD, SETTINGS }
enum class TxFilterType { SEND, RECEIVE, MINT, BURN }
enum class SortField { NONE, TYPE, AMOUNT }

data class DisplayTransaction(
    val tx: Transaction,
    val delta: Int,
    val isUnsynced: Boolean
)

data class PeerOption(val id: String, val label: String)

data class PendingAction(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val amount: Int,
    val targetId: String? = null
)

class WalletViewModel(
    private val settingsService: SettingsService,
    private val ledger: Ledger,
    private val ledgerService: LedgerService
) : ViewModel() {

    var currentScreen by mutableStateOf(AppScreenState.HOME)
    var availableAccounts by mutableStateOf(settingsService.accounts ?: emptyList()); private set

    var detectedAccount by mutableStateOf<StoredAccount?>(null)
    var physicallyConnectedCardAccount by mutableStateOf<StoredAccount?>(null); private set
    var isNewCardDetected by mutableStateOf(false); private set
    var currentDetectedCardId by mutableStateOf<String?>(null); private set
    var isCardConnected by mutableStateOf(false); private set
    var newCardHasPin by mutableStateOf(false); private set

    var isSettingsLoading by mutableStateOf(false); private set

    var isSetupLoading by mutableStateOf(false); private set
    var isSetupSuccessful by mutableStateOf(false); private set
    private var tempSetupAccount: StoredAccount? = null
    private var tempSetupPin: String? = null

    var balance by mutableStateOf(0); private set
    var unsyncedPendingSum: Long by mutableStateOf(0); private set

    var pendingActions by mutableStateOf<List<PendingAction>>(emptyList()); private set
    private var _unsyncedTransactions by mutableStateOf<List<DisplayTransaction>>(emptyList())

    val sessionTransactions = mutableStateListOf<DisplayTransaction>()
    private var _fullHistory = mutableStateListOf<DisplayTransaction>()

    var knownNetworkPeers by mutableStateOf<List<PeerOption>>(emptyList()); private set

    var errorMessage by mutableStateOf<String?>(null); private set
    var syncStatus by mutableStateOf<String?>(null); private set
    var currentId by mutableStateOf<String?>(null); private set
    var currentName by mutableStateOf("Unknown"); private set

    var isSearchMode by mutableStateOf(false)
    var filterTypes = mutableStateListOf<TxFilterType>()
    var filterPeerQuery by mutableStateOf("")

    var sortField by mutableStateOf(SortField.NONE)
    var sortAscending by mutableStateOf(false)

    val filteredHistory by derivedStateOf {
        val rawQuery = filterPeerQuery.trim().lowercase()
        val selectedTypes = if (filterTypes.isEmpty()) TxFilterType.entries.toSet() else filterTypes.toSet()

        val hasSendOrReceive = selectedTypes.contains(TxFilterType.SEND) || selectedTypes.contains(TxFilterType.RECEIVE)
        val shouldCheckPeer = rawQuery.isNotEmpty() && hasSendOrReceive

        val filtered = _fullHistory.filter { item ->
            val tx = item.tx
            val isMe = tx.author() == currentId

            val typeEnum = when {
                tx is MintTransaction -> TxFilterType.MINT
                tx is BurnTransaction -> TxFilterType.BURN
                tx is SendTransaction && isMe -> TxFilterType.SEND
                tx is SendTransaction && !isMe -> TxFilterType.RECEIVE
                else -> null
            }
            if (typeEnum == null || !selectedTypes.contains(typeEnum)) return@filter false

            if (shouldCheckPeer) {
                val peerId = when {
                    tx is SendTransaction && isMe -> tx.target()
                    tx is SendTransaction && !isMe -> tx.author()
                    else -> tx.author()
                }
                val peerName = knownNetworkPeers.find { it.id == peerId }?.label?.lowercase() ?: ""
                val idMatch = peerId.lowercase().contains(rawQuery)
                val nameMatch = peerName.contains(rawQuery)

                if (!idMatch && !nameMatch) return@filter false
            }
            true
        }

        when (sortField) {
            SortField.AMOUNT -> {
                if (sortAscending) {
                    filtered.sortedBy { getSignedAmount(it) }
                } else {
                    filtered.sortedByDescending { getSignedAmount(it) }
                }
            }
            SortField.TYPE -> {
                if (sortAscending) {
                    filtered.sortedBy { getSortableType(it) }
                } else {
                    filtered.sortedByDescending { getSortableType(it) }
                }
            }
            SortField.NONE -> filtered
        }
    }

    private fun getSignedAmount(item: DisplayTransaction): Int {
        val tx = item.tx
        val isMe = tx.author() == currentId
        return when {
            tx is BurnTransaction -> -item.delta
            tx is SendTransaction && isMe -> -item.delta
            else -> item.delta
        }
    }

    private fun getSortableType(item: DisplayTransaction): Int {
        val tx = item.tx
        val isMe = tx.author() == currentId
        return when {
            tx is MintTransaction -> 1
            tx is BurnTransaction -> 2
            tx is SendTransaction && isMe -> 3
            else -> 4
        }
    }

    private var walletService: WalletService? = null
    private var card: SmartCard? = null
    private var sessionPin: String? = null
    private var cardPollingJob: Job? = null
    private var setupJob: Job? = null
    private var settingsJob: Job? = null

    init {
        startCardPolling()
    }

    fun cycleSort(field: SortField) {
        if (sortField == field) {
            if (!sortAscending) {
                sortAscending = true
            } else {
                sortField = SortField.NONE
                sortAscending = false
            }
        } else {
            sortField = field
            sortAscending = false
        }
    }

    fun send(targetId: String, amount: Int) {
        val action = PendingAction(type = "SEND", amount = amount, targetId = targetId)
        handleAction(action) { ws -> ws.send(targetId, amount) }
    }

    fun mint(amount: Int) {
        val action = PendingAction(type = "MINT", amount = amount)
        handleAction(action) { ws -> ws.mint(amount) }
    }

    fun burn(amount: Int) {
        val action = PendingAction(type = "BURN", amount = amount)
        handleAction(action) { ws -> ws.burn(amount) }
    }

    private fun handleAction(action: PendingAction, execute: suspend (WalletService) -> Transaction) {
        addToPending(action)
        if (isCardConnected && walletService != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val tx = execute(walletService!!)
                    withContext(Dispatchers.Main) {
                        addTxToSessionList(tx, action.amount)
                        pendingActions = pendingActions.filter { it.id != action.id }
                        savePendingToDisk()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { errorMessage = e.message }
                }
            }
        }
    }

    private fun addTxToSessionList(tx: Transaction, delta: Int) {
        if (sessionTransactions.none { it.tx.id() == tx.id() }) {
            sessionTransactions.add(0, DisplayTransaction(tx, delta, false))
        }
    }

    fun getUnsyncedIncoming(): List<DisplayTransaction> = _unsyncedTransactions

    fun selectAccountToLogin(acc: StoredAccount) {
        detectedAccount = acc
        currentScreen = AppScreenState.LOGIN
        errorMessage = null
    }

    fun attemptLogin(pin: String) {
        val acc = detectedAccount ?: return
        if (acc.pinHash().isNotEmpty()) {
            val inputHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(pin.toByteArray(StandardCharsets.US_ASCII)))
            if (inputHash != acc.pinHash()) {
                errorMessage = Constants.ERR_PIN_INVALID
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val c = card
            var isPinCorrectOnCard = false
            if (c != null) {
                val matchesPhysical = physicallyConnectedCardAccount?.id() == acc.id()
                if (matchesPhysical || acc.pinHash().isEmpty()) {
                    try {
                        if (!c.isConnected) c.connect()
                        if (c.verifyPin(pin.toByteArray(StandardCharsets.US_ASCII))) isPinCorrectOnCard = true
                    } catch (_: Exception) { }
                }
            }
            val allowLogin = if (acc.pinHash().isEmpty()) isPinCorrectOnCard else true
            withContext(Dispatchers.Main) {
                if (allowLogin) {
                    if (acc.pinHash().isEmpty() && isPinCorrectOnCard) {
                        val newHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(pin.toByteArray(StandardCharsets.US_ASCII)))
                        settingsService.saveAccount(acc.id(), acc.name(), newHash)
                        availableAccounts = settingsService.accounts
                    }
                    performLogin(acc, pin, if (isPinCorrectOnCard) c else null)
                } else {
                    errorMessage = Constants.ERR_PIN_INVALID
                }
            }
        }
    }

    private fun performLogin(account: StoredAccount, pin: String, connectedCard: SmartCard?) {
        this.sessionPin = pin
        currentId = account.id()
        currentName = account.name()
        errorMessage = null

        sessionTransactions.clear()
        _fullHistory.clear()

        this.balance = settingsService.getLastBalance(account.id())
        loadPendingFromDisk()

        if (connectedCard != null) {
            connectWalletService(connectedCard, pin)
        } else {
            isCardConnected = false
            loadOfflineData(account.id())
        }
        currentScreen = AppScreenState.DASHBOARD
    }

    private fun loadOfflineData(uid: String) {
        ledgerService.observeRelevantLogs(uid) { logs ->
            updateHistoryFromLogs(logs, uid, emptySet())
        }
    }

    private fun updateHistoryFromLogs(allLogs: List<LedgerEntry>, uid: String, recentlySyncedIds: Set<String>) {
        val allTx = allLogs.mapNotNull { mapLogToTransaction(it) }
        val balanceResult = ledgerService.calculateBalance(allTx, uid)

        val peers = allLogs.map { it.authorID }.toSet()
        updateKnownPeers(peers)

        _fullHistory.clear()
        balanceResult.items.forEach { d ->
            val isNew = recentlySyncedIds.contains(d.tx().id())
            if (isNew) addTxToSessionList(d.tx(), d.delta().toInt())
            _fullHistory.add(DisplayTransaction(d.tx(), d.delta().toInt(), false))
        }

        this.balance = balanceResult.totalBalance
    }

    private fun mapLogToTransaction(entry: LedgerEntry): Transaction? {
        val type = Constants.OperationType.fromCode(entry.type)
        val id = CryptoUtils.bytesToHex(entry.getHash())
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

    private fun connectWalletService(card: SmartCard, pin: String) {
        isCardConnected = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ws = WalletService(card, pin.toCharArray(), settingsService, ledgerService, ledger)

                ws.setOnStateUpdateListener { state ->
                    viewModelScope.launch(Dispatchers.Main) {
                        updateKnownPeers(state.knownPeers)

                        _unsyncedTransactions = state.unsyncedIncoming.map { DisplayTransaction(it, 0, true) }
                        unsyncedPendingSum = state.unsyncedPendingSum

                        _fullHistory.clear()
                        state.fullHistory.forEach { d ->
                            _fullHistory.add(DisplayTransaction(d.tx(), d.delta().toInt(), false))
                        }

                        state.recentlySynced.forEach { d ->
                            addTxToSessionList(d.tx(), d.delta().toInt())
                        }

                        balance = state.balance
                        if (currentId != null) settingsService.saveBalance(currentId!!, balance)
                    }
                }
                ws.start()
                this@WalletViewModel.walletService = ws
                processLocalPendingQueue()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCardConnected = false
                    walletService = null
                    if (!e.message.toString().contains(Constants.ERR_CODE_CARD_REMOVED)) {
                        errorMessage = "Wallet Error: ${e.message}"
                    }
                }
            }
        }
    }

    private fun updateKnownPeers(servicePeers: Set<String> = emptySet()) {
        val uid = currentId ?: return
        val diskPeers = settingsService.getKnownPeers(uid)
        val allIds = (diskPeers + servicePeers).toSet()
        this.knownNetworkPeers = allIds.map { peerId ->
            val localAcc = availableAccounts.find { it.id() == peerId }
            val name = localAcc?.name() ?: "User ...${peerId.takeLast(6)}"
            PeerOption(peerId, name)
        }.filter { it.id != uid }
    }

    private fun addToPending(action: PendingAction) {
        val newList = pendingActions.toMutableList()
        newList.add(0, action)
        pendingActions = newList
        savePendingToDisk()
    }

    private suspend fun processLocalPendingQueue() {
        val ws = walletService ?: return
        val list = pendingActions.reversed()
        if (list.isEmpty()) return

        withContext(Dispatchers.Main) { syncStatus = "Syncing..." }
        try {
            for (action in list) {
                val tx = when (action.type) {
                    "MINT" -> ws.mint(action.amount)
                    "BURN" -> ws.burn(action.amount)
                    "SEND" -> if (action.targetId != null) ws.send(action.targetId, action.amount) else null
                    else -> null
                }
                if (tx != null) {
                    withContext(Dispatchers.Main) {
                        addTxToSessionList(tx, action.amount)
                        pendingActions = pendingActions.filter { it.id != action.id }
                        savePendingToDisk()
                    }
                }
                delay(300)
            }
            withContext(Dispatchers.Main) { syncStatus = null }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                errorMessage = "Sync paused: ${e.message}"
                syncStatus = "Paused"
            }
        }
    }

    private fun savePendingToDisk() {
        val uid = currentId ?: return
        val strings = pendingActions.map { "${it.type}:${it.amount}:${it.targetId ?: ""}" }
        settingsService.savePendingActions(uid, strings)
    }

    private fun loadPendingFromDisk() {
        val uid = currentId ?: return
        val loaded = settingsService.loadPendingActions(uid).mapNotNull {
            try {
                val p = it.split(":")
                val target = if (p.size > 2 && p[2].isNotEmpty()) p[2] else null
                PendingAction(type = p[0], amount = p[1].toInt(), targetId = target)
            } catch (_: Exception) {
                null
            }
        }
        pendingActions = loaded
    }

    private fun startCardPolling() {
        cardPollingJob?.cancel()
        cardPollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!isSetupLoading) checkCardState()
                delay(1000)
            }
        }
    }

    private suspend fun checkCardState() {
        val c = card ?: return
        try {
            if (!c.isConnected) {
                try {
                    c.connect()
                } catch (_: Exception) {
                    handleCardRemoval()
                    return
                }
            }
            val pubKey = c.getPublicKey()
            val idHex = CryptoUtils.getPersonIdAsHex(pubKey)
            val isPinSet = try { c.isPinSet() } catch (_: Exception) { false }
            val identifiedAccount = settingsService.accounts.find { it.id() == idHex }

            withContext(Dispatchers.Main) {
                currentDetectedCardId = idHex
                newCardHasPin = isPinSet
                physicallyConnectedCardAccount = identifiedAccount

                if (currentScreen == AppScreenState.DASHBOARD || currentScreen == AppScreenState.SETTINGS) {
                    if (currentId == idHex && !isCardConnected && sessionPin != null) {
                        connectWalletService(c, sessionPin!!)
                    }
                } else if (currentScreen == AppScreenState.HOME) {
                    if (identifiedAccount == null) {
                        isNewCardDetected = true
                        detectedAccount = null
                    } else {
                        isNewCardDetected = false
                        detectedAccount = identifiedAccount
                    }
                }
            }
        } catch (_: Exception) {
            handleCardRemoval()
        }
    }

    private suspend fun handleCardRemoval() {
        withContext(Dispatchers.Main) {
            isNewCardDetected = false
            currentDetectedCardId = null
            physicallyConnectedCardAccount = null
            if (isCardConnected) {
                isCardConnected = false
                try {
                    walletService?.close()
                } catch (_: Exception) {}
                walletService = null
            }
        }
        try {
            card?.disconnect()
        } catch (_: Exception) {}
    }

    fun goToSetup() {
        currentScreen = AppScreenState.SETUP
        errorMessage = null
        isSetupLoading = false
        isSetupSuccessful = false
    }

    fun registerNewCard(pin: String, name: String) {
        isSetupLoading = true
        errorMessage = null
        setupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentCard = card ?: throw Exception("No card")
                if (!currentCard.isConnected) currentCard.connect()
                val pinB = pin.toByteArray(StandardCharsets.US_ASCII)
                if (newCardHasPin) {
                    if (!currentCard.verifyPin(pinB)) throw Exception("Incorrect PIN")
                } else {
                    if (!currentCard.isPinSet()) {
                        if (!currentCard.changePin(pinB)) throw Exception("Failed to set PIN")
                    }
                    if (!currentCard.verifyPin(pinB)) throw Exception("PIN verification failed")
                }
                val id = CryptoUtils.getPersonIdAsHex(currentCard.getPublicKey())
                val pinHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(pinB))
                settingsService.saveAccount(id, name, pinHash)
                withContext(Dispatchers.Main) { availableAccounts = settingsService.accounts }
                val tempWs = WalletService(currentCard, pin.toCharArray(), settingsService, ledgerService, ledger)
                tempWs.start()
                if (!currentCard.isGenesisDone()) tempWs.initGenesisTx()
                tempWs.close()
                val newAcc = settingsService.accounts.find { it.id() == id }!!
                withContext(Dispatchers.Main) {
                    isSetupLoading = false
                    isSetupSuccessful = true
                    tempSetupAccount = newAcc
                    tempSetupPin = pin
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSetupLoading = false
                    errorMessage = e.message
                }
            }
        }
    }

    fun completeSetup() {
        if (tempSetupAccount != null && tempSetupPin != null) {
            performLogin(tempSetupAccount!!, tempSetupPin!!, card)
        } else {
            currentScreen = AppScreenState.HOME
        }
        isSetupSuccessful = false
        tempSetupAccount = null
        tempSetupPin = null
    }

    fun goToSettings() {
        currentScreen = AppScreenState.SETTINGS
    }

    fun updateAccountName(newName: String) {
        val uid = currentId ?: return
        val acc = settingsService.accounts.find { it.id() == uid } ?: return
        settingsService.saveAccount(uid, newName, acc.pinHash())
        currentName = newName
        detectedAccount = StoredAccount(uid, newName, acc.pinHash())
        availableAccounts = settingsService.accounts
    }

    fun changeCardPin(newPin: String, onSuccess: () -> Unit, onError: () -> Unit) {
        settingsJob?.cancel()
        isSettingsLoading = true

        settingsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var c = card
                while (isActive) {
                    try {
                        if (c == null) c = card
                        if (c != null && !c.isConnected) {
                            c.connect()
                        }
                        if (c != null && c.isConnected) {
                            break
                        }
                    } catch (_: Exception) { }
                    delay(500)
                }
                if (!isActive) return@launch
                val activeCard = c!!

                val newPinBytes = newPin.toByteArray(StandardCharsets.US_ASCII)
                if (!activeCard.changePin(newPinBytes)) {
                    throw Exception("Failed to change PIN on card")
                }

                val uid = currentId!!
                val acc = settingsService.accounts.find { it.id() == uid }!!
                val newHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(newPinBytes))
                settingsService.saveAccount(uid, acc.name(), newHash)
                sessionPin = newPin

                try {
                    walletService?.close()
                    connectWalletService(activeCard, newPin)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                withContext(Dispatchers.Main) {
                    availableAccounts = settingsService.accounts
                    isSettingsLoading = false
                    onSuccess()
                }
            } catch (e: Exception) {
                if (isActive) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        isSettingsLoading = false
                        onError()
                    }
                }
            }
        }
    }

    fun deleteCurrentAccount() {
        val uid = currentId ?: return
        ledger.removeAccount(uid)
        settingsService.removeAccount(uid)
        availableAccounts = settingsService.accounts
        logout()
    }

    fun cancelSettingsOperation() {
        settingsJob?.cancel()
        settingsJob = null
        isSettingsLoading = false
    }

    fun logout() {
        setupJob?.cancel()
        setupJob = null
        try {
            walletService?.close()
        } catch (_: Exception) {}

        val lastAccount = availableAccounts.find { it.id() == currentId }
        if (lastAccount != null) {
            detectedAccount = lastAccount
        }

        walletService = null
        currentId = null
        sessionPin = null
        isCardConnected = false
        currentScreen = AppScreenState.HOME
        pendingActions = emptyList()

        sessionTransactions.clear()
        _fullHistory.clear()

        isSearchMode = false
        filterTypes.clear()
        filterPeerQuery = ""

        sortField = SortField.NONE
        sortAscending = false

        syncStatus = null
        try {
            card?.disconnect()
        } catch (_: Exception) {}
    }

    fun dismissError() {
        errorMessage = null
    }

    fun cancelAuth() {
        logout()
    }

    fun setCardImplementation(c: SmartCard) {
        this.card = c
    }

    fun toggleFilterType(type: TxFilterType) {
        if (filterTypes.contains(type)) filterTypes.remove(type)
        else filterTypes.add(type)
    }
}