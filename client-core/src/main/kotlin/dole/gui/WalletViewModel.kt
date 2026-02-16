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
import java.util.concurrent.atomic.AtomicBoolean

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
    var setupTargetCardId by mutableStateOf<String?>(null); private set
    var tempSetupAccount: StoredAccount? = null; private set

    var balance by mutableStateOf(0); private set
    var unsyncedPendingSum: Long by mutableStateOf(0); private set
    var pendingActions by mutableStateOf<List<PendingAction>>(emptyList()); private set
    val sessionTransactions = mutableStateListOf<DisplayTransaction>()

    var knownNetworkPeers by mutableStateOf<List<PeerOption>>(emptyList()); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var syncStatus by mutableStateOf<String?>(null); private set
    var currentId by mutableStateOf<String?>(null); private set
    var currentName by mutableStateOf("Unknown"); private set
    var userMessage by mutableStateOf<String?>(null); private set

    var isSearchMode by mutableStateOf(false); private set
    var filterTypes = mutableStateListOf<TxFilterType>()
    var filterPeerQuery by mutableStateOf("")
    var sortField by mutableStateOf(SortField.NONE)
    var sortAscending by mutableStateOf(false)

    var isMinter by mutableStateOf(false); private set

    val isBalancePending by derivedStateOf {
        pendingActions.isNotEmpty() || _unsyncedTransactions.isNotEmpty()
    }

    val filteredHistory by derivedStateOf {
        val rawQuery = filterPeerQuery.trim().lowercase()
        val selectedTypes = if (filterTypes.isEmpty()) TxFilterType.entries.toSet() else filterTypes.toSet()
        val hasSendOrReceive = selectedTypes.contains(TxFilterType.SEND) || selectedTypes.contains(TxFilterType.RECEIVE)
        val shouldCheckPeer = rawQuery.isNotEmpty() && hasSendOrReceive

        val pendingAsDisplay = pendingActions.map { action ->
            val dummyId = "pending-${action.id}"
            val tx: Transaction = when (action.type) {
                "MINT" -> MintTransaction(dummyId, Int.MAX_VALUE, currentId ?: "", action.amount.toLong())
                "BURN" -> BurnTransaction(dummyId, Int.MAX_VALUE, currentId ?: "", action.amount.toLong())
                "SEND" -> SendTransaction(dummyId, Int.MAX_VALUE, currentId ?: "", action.targetId ?: "?", action.amount.toLong())
                else -> MintTransaction(dummyId, 0, "", 0)
            }
            DisplayTransaction(tx, action.amount, isUnsynced = true)
        }

        val combinedList = _fullHistory + pendingAsDisplay + _unsyncedTransactions.filter { unsynced ->
            _fullHistory.none { it.tx.id() == unsynced.tx.id() }
        }

        val uniqueList = combinedList.distinctBy { it.tx.id() }
        val filtered = uniqueList.filter { item ->
            val tx = item.tx
            val isMe = tx.author() == currentId
            val typeEnum = when (tx) {
                is MintTransaction -> TxFilterType.MINT
                is BurnTransaction -> TxFilterType.BURN
                is SendTransaction -> if (isMe) TxFilterType.SEND else TxFilterType.RECEIVE
                else -> null
            }

            if (typeEnum == null || !selectedTypes.contains(typeEnum)) return@filter false

            if (shouldCheckPeer) {
                val peerId =
                    when (tx) {
                        is SendTransaction -> if (isMe) tx.target() else tx.author()
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
            SortField.NONE -> filtered.sortedByDescending {
                if (it.isUnsynced) Long.MAX_VALUE else it.tx.seq().toLong()
            }
        }
    }

    private var tempSetupPin: String? = null
    private var _unsyncedTransactions by mutableStateOf<List<DisplayTransaction>>(emptyList())
    private var _fullHistory = mutableStateListOf<DisplayTransaction>()
    private var walletService: WalletService? = null
    private var card: SmartCard? = null
    private var sessionPin: String? = null
    private var cardPollingJob: Job? = null
    private var setupJob: Job? = null
    private var settingsJob: Job? = null
    private val isSyncing = AtomicBoolean(false)

    init {
        startCardPolling()
    }

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
                        val retries = try { c.getPinRetries() } catch(_:Exception) { 3 }
                        if (retries == 0) c.disconnect()
                        else if (c.verifyPin(pin.toByteArray(StandardCharsets.US_ASCII))) isPinCorrectOnCard = true
                        else c.disconnect()
                    } catch (_: Exception) {
                        try {
                            c.disconnect()
                        } catch(_: Exception) {} }
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

    fun logout() {
        setupJob?.cancel()
        setupJob = null

        val lastAccount = availableAccounts.find { it.id() == currentId }
        if (lastAccount != null) detectedAccount = lastAccount

        resetSessionState()
        currentScreen = AppScreenState.HOME

        try {
            card?.disconnect()
        } catch (_: Exception) {}
    }

    fun send(targetId: String, amount: Int) {
        val action = PendingAction(type = "SEND", amount = amount, targetId = targetId)
        handleAction(action)
    }

    fun mint(amount: Int) {
        val action = PendingAction(type = "MINT", amount = amount)
        handleAction(action)
    }

    fun burn(amount: Int) {
        val action = PendingAction(type = "BURN", amount = amount)
        handleAction(action)
    }

    fun showUserMessage(msg: String) {
        userMessage = msg
    }

    fun dismissUserMessage() {
        userMessage = null
    }

    fun getPeerName(id: String): String? {
        return availableAccounts.find { it.id() == id }?.name()
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

    fun toggleSearchMode() {
        val newMode = !isSearchMode
        isSearchMode = newMode
        ledgerService.setHistorySyncMode(newMode)

        viewModelScope.launch(Dispatchers.IO) {
            walletService?.setSearchMode(newMode)
        }

        if (walletService == null && currentId != null) loadOfflineData(currentId!!)
    }

    fun toggleFilterType(type: TxFilterType) {
        if (filterTypes.contains(type)) filterTypes.remove(type)
        else filterTypes.add(type)
    }

    fun goToSettings() {
        currentScreen = AppScreenState.SETTINGS
    }

    fun goToSetup() {
        if (currentId != null) resetSessionState()
        setupTargetCardId = currentDetectedCardId
        currentScreen = AppScreenState.SETUP
        errorMessage = null
        isSetupLoading = false
        isSetupSuccessful = false
    }

    fun cancelAuth() {
        logout()
    }

    fun dismissError() {
        errorMessage = null
    }

    fun setCardImplementation(c: SmartCard) {
        this.card = c
    }

    fun getUnsyncedIncoming(): List<DisplayTransaction> = _unsyncedTransactions

    fun registerNewCard(pin: String, name: String) {
        isSetupLoading = true
        errorMessage = null
        setupJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var activeCard: SmartCard? = null
                while (isActive) {
                    try {
                        val currentCard = card ?: throw Exception("Service lost")
                        if (!currentCard.isConnected) {
                            try {
                                currentCard.connect()
                            } catch(_:Exception){}
                        }
                        if (currentCard.isConnected) {
                            val id = CryptoUtils.getPersonIdAsHex(currentCard.getPublicKey())
                            if (setupTargetCardId != null && id == setupTargetCardId) {
                                activeCard = currentCard
                                break
                            } else if (setupTargetCardId == null) {
                                activeCard = currentCard
                                break
                            } else {
                                try {
                                    currentCard.disconnect()
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {
                        try {
                            card?.disconnect()
                        } catch (_: Exception) {}
                    }
                    delay(500)
                }

                if (!isActive) return@launch

                val c = activeCard!!
                val retries = try { c.getPinRetries() } catch(_: Exception) { 3 }
                if (retries == 0) {
                    withContext(Dispatchers.Main) {
                        isSetupLoading = false
                        currentScreen = AppScreenState.HOME
                        errorMessage = "Card is bricked!"
                    }
                    return@launch
                }

                val pinB = pin.toByteArray(StandardCharsets.US_ASCII)
                try {
                    if (newCardHasPin) {
                        if (!verifyPinOrAbort(c, pinB)) return@launch
                    } else {
                        if (!c.isPinSet()) {
                            if (!c.changePin(pinB)) {
                                c.disconnect()
                                throw Exception("Failed to set PIN")
                            }
                        }
                        if (!verifyPinOrAbort(c, pinB)) return@launch
                    }
                } catch (e: Exception) {
                    throw e
                }

                val isMinter = try {
                    c.isMinter()
                } catch(_: Exception) { false }

                val id = CryptoUtils.getPersonIdAsHex(c.getPublicKey())
                val pinHash = CryptoUtils.bytesToHex(CryptoUtils.sha256(pinB))
                settingsService.saveAccount(id, name, pinHash)
                settingsService.setMinterStatus(id, isMinter)

                withContext(Dispatchers.Main) { availableAccounts = settingsService.accounts }

                val tempWs = WalletService(c, pin.toCharArray(), settingsService, ledgerService, ledger)
                if (!c.isGenesisDone()) tempWs.initGenesisTx()
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
            val isSameCard = currentDetectedCardId == tempSetupAccount!!.id()
            val cardToLogin = if (isSameCard) card else null
            performLogin(tempSetupAccount!!, tempSetupPin!!, cardToLogin)
        } else {
            currentScreen = AppScreenState.HOME
        }
        isSetupSuccessful = false
        tempSetupAccount = null
        tempSetupPin = null
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
                        if (c != null && !c.isConnected) c.connect()
                        if (c != null && c.isConnected) break
                    } catch (_: Exception) { }
                    delay(500)
                }
                if (!isActive) return@launch
                val activeCard = c!!
                val newPinBytes = newPin.toByteArray(StandardCharsets.US_ASCII)
                if (!activeCard.changePin(newPinBytes)) throw Exception("Failed to change PIN on card")
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

    private fun performLogin(account: StoredAccount, pin: String, connectedCard: SmartCard?) {
        this.sessionPin = pin
        currentId = account.id()
        currentName = account.name()
        errorMessage = null
        sessionTransactions.clear()
        _fullHistory.clear()
        val savedBal = settingsService.getLastBalance(account.id())
        this.balance = savedBal

        this.isMinter = settingsService.isMinter(account.id())

        loadPendingFromDisk()
        if (connectedCard != null) {
            connectWalletService(connectedCard, pin)
        } else {
            isCardConnected = false
            loadOfflineData(account.id())
        }
        currentScreen = AppScreenState.DASHBOARD
    }

    private fun connectWalletService(card: SmartCard, pin: String) {
        if (isCardConnected) return
        isCardConnected = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!card.isConnected) {
                    try {
                        card.connect()
                    } catch(_:Exception) {}
                }
                val realCardId = try {
                    CryptoUtils.getPersonIdAsHex(card.getPublicKey())
                } catch (_: Exception) {
                    throw Exception("Card read failed during connection")
                }
                if (realCardId != currentId) throw Exception("Card mismatch! Expected $currentId but found $realCardId")

                val cardMinterStatus = try {
                    card.isMinter()
                } catch(_: Exception) { false }
                settingsService.setMinterStatus(currentId!!, cardMinterStatus)
                withContext(Dispatchers.Main) {
                    isMinter = cardMinterStatus
                }

                val ws = WalletService(card, pin.toCharArray(), settingsService, ledgerService, ledger)
                if (isSearchMode) ws.setSearchMode(true)
                ws.setOnStateUpdateListener { state ->
                    viewModelScope.launch(Dispatchers.Main) {
                        updateKnownPeers(state.knownPeers)

                        _unsyncedTransactions = state.unsyncedIncoming.map { DisplayTransaction(it, 0, true) }
                        unsyncedPendingSum = state.unsyncedPendingSum
                        val unsyncedIds = state.unsyncedIncoming.map { it.id() }.toSet()
                        _fullHistory.clear()
                        state.fullHistory.forEach { d ->
                            val isUnsynced = unsyncedIds.contains(d.tx().id())
                            _fullHistory.add(DisplayTransaction(d.tx(), d.delta().toInt(), isUnsynced))
                        }
                        state.recentlySynced.asReversed().forEach { d ->
                            addTxToSessionList(d.tx(), d.delta().toInt())
                        }
                        balance = state.confirmedBalance
                        if (state.unsyncedIncoming.isNotEmpty() || pendingActions.isNotEmpty()) processSyncQueue()
                    }
                }
                ws.start()
                this@WalletViewModel.walletService = ws
                processSyncQueue()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCardConnected = false
                    walletService = null
                    if (!e.message.toString().contains(Constants.ERR_CODE_CARD_REMOVED) &&
                        !e.message.toString().contains("Card mismatch")) {
                        errorMessage = "Wallet Error: ${e.message}"
                    }
                }
                try {
                    card.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadOfflineData(uid: String) {
        val lastReceived = settingsService.getLastReceivedGocs(uid)
        val lastSent = settingsService.getLastSentGocs(uid)
        val accountState = settingsService.getInitialAccountState(uid)
        val lastSeq = settingsService.getLastKnownSeq(uid)

        val snapshotBalance = calculateBalanceFromSnapshots(lastReceived, lastSent, accountState.totalMinted, accountState.totalBurned)
        this.balance = snapshotBalance

        ledgerService.setHistorySyncMode(isSearchMode)
        ledgerService.observeRelevantLogs(uid, lastSeq, lastReceived) { logs ->
            updateHistoryFromLogs(
                logs, uid,
                emptySet(),
                lastReceived,
                lastSent,
                accountState.totalMinted,
                accountState.totalBurned
            )
        }
    }

    private fun updateHistoryFromLogs(
        allLogs: List<LedgerEntry>,
        uid: String,
        recentlySyncedIds: Set<String>,
        savedReceivedGocs: Map<String, Int> = emptyMap(),
        savedSentGocs: Map<String, Int> = emptyMap(),
        savedMintGoc: Int = 0,
        savedBurnGoc: Int = 0
    ) {
        val allTx = allLogs.mapNotNull { mapLogToTransaction(it) }
        val balanceResult = ledgerService.calculateBalance(allTx, uid)
        val peers = allLogs.map { it.authorID }.toSet()
        updateKnownPeers(peers)
        _fullHistory.clear()
        val unsyncedList = mutableListOf<DisplayTransaction>()
        var offlineUnsyncedSum: Long = 0
        var efficientModeLogSum: Long = 0

        balanceResult.items.forEach { d ->
            val tx = d.tx()
            val txId = tx.id()
            val isNewSession = recentlySyncedIds.contains(txId)
            var displayDelta = d.delta().toInt()

            if (tx is MintTransaction && tx.author() == uid) {
                if (d.delta() == tx.goc() && savedMintGoc > 0) {
                    val corrected = tx.goc() - savedMintGoc
                    if (corrected > 0) displayDelta = corrected.toInt()
                }
            } else if (tx is BurnTransaction && tx.author() == uid) {
                if (d.delta() == tx.goc() && savedBurnGoc > 0) {
                    val corrected = tx.goc() - savedBurnGoc
                    if (corrected > 0) displayDelta = corrected.toInt()
                }
            } else if (tx is SendTransaction) {
                if (tx.author() == uid) {
                    if (d.delta() == tx.goc()) {
                        val lastKnown = savedSentGocs[tx.target()] ?: 0
                        if (lastKnown > 0 && tx.goc() > lastKnown) displayDelta = (tx.goc() - lastKnown).toInt()
                    }
                } else if (tx.target() == uid) {
                    if (d.delta() == tx.goc()) {
                        val lastKnown = savedReceivedGocs[tx.author()] ?: 0
                        if (lastKnown > 0 && tx.goc() > lastKnown) displayDelta = (tx.goc() - lastKnown).toInt()
                    }
                }
            }

            val sign =
                when (tx) {
                    is MintTransaction -> 1
                    is BurnTransaction -> -1
                    is SendTransaction if tx.target() == uid -> 1
                    else -> -1
                }
            efficientModeLogSum += (displayDelta * sign)

            var isUnsynced = false
            if (walletService == null && tx is SendTransaction && tx.target() == uid) {
                val knownGoc = savedReceivedGocs[tx.author()] ?: 0
                if (tx.goc() > knownGoc) {
                    isUnsynced = true
                    unsyncedList.add(DisplayTransaction(tx, displayDelta, true))
                    offlineUnsyncedSum += displayDelta
                }
            }
            if (isNewSession) addTxToSessionList(tx, displayDelta)
            _fullHistory.add(DisplayTransaction(tx, displayDelta, isUnsynced))
        }

        if (walletService == null) {
            _unsyncedTransactions = unsyncedList
            unsyncedPendingSum = offlineUnsyncedSum
        }

        if (isSearchMode) {
            this.balance = (balanceResult.totalBalance - offlineUnsyncedSum).toInt()
        } else {
            val snapshotBal = calculateBalanceFromSnapshots(savedReceivedGocs, savedSentGocs, savedMintGoc, savedBurnGoc)
            this.balance = (snapshotBal + efficientModeLogSum - offlineUnsyncedSum).toInt()
        }
    }

    private fun handleAction(action: PendingAction) {
        addToPending(action)
        if (isCardConnected && walletService != null) processSyncQueue()
    }

    private fun processSyncQueue() {
        val ws = walletService ?: return
        if (isSyncing.get()) return
        isSyncing.set(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    var worked = false
                    val incoming = _unsyncedTransactions.map { it.tx }
                    if (incoming.isNotEmpty()) {
                        withContext(Dispatchers.Main) { syncStatus = "Receiving..." }
                        try {
                            ws.syncIncomingTransactions(incoming)
                            worked = true
                        } catch (_: Exception) { }
                    }
                    val outgoingList = pendingActions.reversed()
                    if (outgoingList.isNotEmpty()) {
                        val action = outgoingList.first()
                        withContext(Dispatchers.Main) { syncStatus = "Sending..." }
                        try {
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
                                worked = true
                            }
                        } catch (e: Exception) {
                            if (e.message.toString().contains("6985") || e.message.toString().contains("Conditions not satisfied")) {
                                withContext(Dispatchers.Main) {
                                    pendingActions = pendingActions.filter { it.id != action.id }
                                    savePendingToDisk()
                                }
                                worked = true
                            } else {
                                throw e
                            }
                        }
                        delay(200)
                    }
                    if (!worked) break
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Sync paused: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    syncStatus = null
                }
                isSyncing.set(false)
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

    private fun addTxToSessionList(tx: Transaction, delta: Int) {
        if (sessionTransactions.none { it.tx.id() == tx.id() }) {
            sessionTransactions.add(0, DisplayTransaction(tx, delta, false))
        }
    }

    private fun calculateBalanceFromSnapshots(
        received: Map<String, Int>,
        sent: Map<String, Int>,
        mint: Int,
        burn: Int
    ): Int {
        val totalReceived = received.values.sumOf { it.toLong() }
        val totalSent = sent.values.sumOf { it.toLong() }
        return (mint.toLong() - burn.toLong() - totalSent + totalReceived).toInt()
    }

    private fun addToPending(action: PendingAction) {
        val newList = pendingActions.toMutableList()
        newList.add(0, action)
        pendingActions = newList
        savePendingToDisk()
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

    private fun getSignedAmount(item: DisplayTransaction): Int {
        val tx = item.tx
        val isMe = tx.author() == currentId
        return when (tx) {
            is BurnTransaction -> -item.delta
            is SendTransaction -> if (isMe) -item.delta else item.delta
            else -> item.delta
        }
    }

    private fun getSortableType(item: DisplayTransaction): Int {
        val tx = item.tx
        val isMe = tx.author() == currentId
        return when (tx) {
            is MintTransaction -> 1
            is BurnTransaction -> 2
            is SendTransaction -> if (isMe) 3 else 4
            else -> 4
        }
    }

    private suspend fun verifyPinOrAbort(activeCard: SmartCard, pinB: ByteArray): Boolean {
        if (!activeCard.verifyPin(pinB)) {
            val left = try { activeCard.getPinRetries() } catch (_: Exception) { 0 }
            activeCard.disconnect()
            if (left == 0) {
                withContext(Dispatchers.Main) {
                    isSetupLoading = false
                    currentScreen = AppScreenState.HOME
                    errorMessage = "Card is bricked!"
                }
                return false
            }
            throw Exception("$left attempts remaining.")
        }
        return true
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
            val retries = try {
                c.getPinRetries()
            } catch(_:Exception) { 3 }
            if (retries == 0) {
                handleCardRemoval()
                return
            }
            val pubKey = c.getPublicKey()
            val idHex = CryptoUtils.getPersonIdAsHex(pubKey)
            val isPinSet = try { c.isPinSet() } catch (_: Exception) { false }
            val identifiedAccount = settingsService.accounts.find { it.id() == idHex }
            withContext(Dispatchers.Main) {
                currentDetectedCardId = idHex
                newCardHasPin = isPinSet
                physicallyConnectedCardAccount = identifiedAccount
                isNewCardDetected = identifiedAccount == null
                if (currentScreen == AppScreenState.DASHBOARD || currentScreen == AppScreenState.SETTINGS) {
                    if (currentId == idHex && !isCardConnected && sessionPin != null) connectWalletService(c, sessionPin!!)
                } else if (currentScreen == AppScreenState.HOME) {
                    detectedAccount = identifiedAccount
                }
            }
        } catch (_: Exception) {
            handleCardRemoval()
        }
    }

    private suspend fun handleCardRemoval() {
        withContext(Dispatchers.Main) {
            isNewCardDetected = false; currentDetectedCardId = null; physicallyConnectedCardAccount = null
            if (isCardConnected) {
                isCardConnected = false
                try {
                    walletService?.close()
                } catch (_: Exception) {}
                walletService = null
                if (currentId != null) {
                    loadOfflineData(currentId!!)
                }
            }
        }
        try {
            card?.disconnect()
        } catch (_: Exception) {}
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

    private fun resetSessionState() {
        try {
            walletService?.close()
        } catch (_: Exception) {}
        walletService = null
        setupTargetCardId = null
        currentId = null
        sessionPin = null
        isCardConnected = false
        syncStatus = null
        tempSetupAccount = null
        tempSetupPin = null
        isSetupSuccessful = false
        pendingActions = emptyList()
        sessionTransactions.clear()
        _fullHistory.clear()
        isSearchMode = false
        filterTypes.clear()
        filterPeerQuery = ""
        sortField = SortField.NONE
        sortAscending = false
        isMinter = false
    }
}