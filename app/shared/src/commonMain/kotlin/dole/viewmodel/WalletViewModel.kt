package dole.viewmodel

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dole.card.SmartCard
import dole.core.CoreWrapper
import dole.data.AccountPreferences
import com.russhwolf.settings.Settings
import dole.data.AccountRegistry
import dole.data.CardSyncState
import dole.data.DeveloperSettings
import dole.data.HistoryFilter
import dole.data.models.BurnTransaction
import dole.data.models.GenesisTransaction
import dole.data.models.MintTransaction
import dole.data.models.SendTransaction
import dole.data.models.StoredAccount
import dole.data.models.LedgerJson
import dole.data.models.Transaction
import dole.data.models.amount
import dole.utils.ProtocolSerializer
import dole.utils.ScreenCaptureProtection
import dole.wallet.WalletService
import dole.wallet.unsyncedIncoming
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

class WalletViewModel(
    private val accounts: AccountRegistry,
    private val preferences: AccountPreferences,
    private val cardSyncState: CardSyncState,
    settings: Settings,
    private val card: SmartCard,
    private val storagePath: String
) {
    private fun Throwable.typeName(): String = this::class.simpleName ?: "Throwable"

    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val queueMutex = Mutex()
    private var walletService: WalletService? = null
    private var sessionPin: String? = null
    private var sessionPublicKeyHex: String? = null

    val developer = DeveloperSettings(
        settings = settings,
        accounts = accounts,
        scope = viewModelScope,
        storagePath = storagePath,
        onAccountsDeleted = {
            availableAccounts = accounts.getAllAccounts()
            detectedAccount = null
            physicallyConnectedCardAccount = null
            isNewCardDetected = false
            logout()
        },
        onLedgerCleared = { clearLedgerState() },
        notify = { showUserMessage(it) },
        reportError = { errorMessage = it }
    )

    var currentScreen by mutableStateOf(AppScreenState.HOME)
    var availableAccounts by mutableStateOf(accounts.getAllAccounts()); private set
    var detectedAccount by mutableStateOf<StoredAccount?>(null)

    var physicallyConnectedCardAccount by mutableStateOf<StoredAccount?>(null); private set
    var isNewCardDetected by mutableStateOf(false); private set
    var currentDetectedCardId by mutableStateOf<String?>(null); private set
    var isCardConnected by mutableStateOf(false); private set
    var newCardHasPin by mutableStateOf(false); private set

    var currentId by mutableStateOf<String?>(null); private set
    var currentName by mutableStateOf("Unknown"); private set

    var balance by mutableLongStateOf(0L); private set
    var isMinter by mutableStateOf(false); private set

    var cardBalance by mutableStateOf<Long?>(null); private set
    var isCardPinOutOfSync by mutableStateOf(false); private set

    var processingActionId by mutableStateOf<String?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var userMessage by mutableStateOf<String?>(null); private set
    var syncStatus by mutableStateOf<String?>(null); private set

    var isSettingsLoading by mutableStateOf(false); private set
    var isSetupLoading by mutableStateOf(false); private set
    var isSetupSuccessful by mutableStateOf(false); private set
    var setupTargetCardId by mutableStateOf<String?>(null); private set
    private var tempSetupAccount: StoredAccount? = null
    private var tempSetupPin: String? = null

    private var _fullHistory = mutableStateListOf<DisplayTransaction>()
    var pendingActions by mutableStateOf<List<PendingAction>>(emptyList()); private set
    private var _unsyncedTransactions by mutableStateOf<List<DisplayTransaction>>(emptyList())
    val sessionTransactions = mutableStateListOf<DisplayTransaction>()
    private var isFirstSync = true

    var knownNetworkPeers by mutableStateOf<List<PeerOption>>(emptyList()); private set
    val history = HistoryFilter()

    val isBalancePending by derivedStateOf { pendingActions.isNotEmpty() || _unsyncedTransactions.isNotEmpty() }

    val displayBalance by derivedStateOf { cardBalance ?: balance }

    val filteredHistory by derivedStateOf {
        val pending = pendingActions.map { action ->
            val id = "pending-${action.id}"
            val author = currentId ?: ""
            val tx: Transaction = when (action.type) {
                "BURN" -> BurnTransaction(id, author, 0L, 0L, "", action.amount)
                "SEND" -> SendTransaction(id, author, 0L, 0L, "", action.targetId ?: "?", action.amount)
                else -> MintTransaction(id, author, 0L, 0L, "", action.amount)
            }
            DisplayTransaction(tx, action.amount, isUnsynced = true)
        }

        val unsynced = _unsyncedTransactions.filter { candidate ->
            _fullHistory.none { it.tx.id == candidate.tx.id }
        }

        history.apply(
            transactions = (_fullHistory + pending + unsynced).distinctBy { it.tx.id },
            currentId = currentId,
            peerName = ::getPeerName
        )
    }

    private var cardPollingJob: Job? = null

    private val rustListener: (Long, String) -> Unit = { balance, historyJson ->
        viewModelScope.launch {
            this@WalletViewModel.balance = balance
            try {
                val txList = LedgerJson.decodeFromString<List<Transaction>>(historyJson)
                val displayList = txList.map { DisplayTransaction(it, it.amount, false) }

                if (isFirstSync) {
                    isFirstSync = false
                } else {
                    val oldIds = _fullHistory.map { it.tx.id }.toSet()
                    val newIncoming = displayList.filter { it.tx.id !in oldIds }

                    for (newTx in newIncoming) {
                        if (sessionTransactions.none { it.tx.id == newTx.tx.id }) {
                            sessionTransactions.add(0, newTx)
                        }
                    }
                }

                _fullHistory.clear()
                _fullHistory.addAll(displayList)

                val peerIds = txList.map {
                    if (it is SendTransaction) { if (it.author == currentId) it.target else it.author } else it.author
                }.toSet().filter { it != currentId }

                knownNetworkPeers = peerIds.map { peerId ->
                    val name = accounts.getAccount(peerId)?.name ?: "User ...${peerId.takeLast(6)}"
                    PeerOption(peerId, name)
                }

                val service = walletService
                val unsyncedIds = service?.let { pushToCard(it, txList) } ?: unsyncedIdsFromStore(txList)
                _unsyncedTransactions = displayList.flagUnsynced(unsyncedIds)
                if (service != null && pendingActions.isEmpty()) card.completeSession()
            } catch (_: Exception) {
            }
        }
    }

    var globalHistory by mutableStateOf<List<DisplayTransaction>>(emptyList()); private set
    var networkAccounts by mutableStateOf<List<NetworkAccountSummary>>(emptyList()); private set

    private val observerListener: (Long, String) -> Unit = { _, historyJson ->
        viewModelScope.launch {
            try {
                val txList = LedgerJson.decodeFromString<List<Transaction>>(historyJson)
                val displayList = txList.map { DisplayTransaction(it, it.amount, false) }
                val balances = LinkedHashMap<String, Long>()
                val txCounts = HashMap<String, Int>()

                for (tx in txList) {
                    when (tx) {
                        is MintTransaction -> balances[tx.author] = (balances[tx.author] ?: 0L) + tx.goc
                        is BurnTransaction -> balances[tx.author] = (balances[tx.author] ?: 0L) - tx.goc
                        is SendTransaction -> {
                            balances[tx.author] = (balances[tx.author] ?: 0L) - tx.goc
                            balances[tx.target] = (balances[tx.target] ?: 0L) + tx.goc
                        }
                        is GenesisTransaction -> balances.getOrPut(tx.author) { 0L }
                    }
                    if (tx !is GenesisTransaction) txCounts[tx.author] = (txCounts[tx.author] ?: 0) + 1
                }

                globalHistory = displayList
                networkAccounts = balances.map { (id, bal) ->
                    NetworkAccountSummary(
                        id = id,
                        name = getPeerName(id),
                        balance = bal,
                        txCount = txCounts[id] ?: 0
                    )
                }.sortedByDescending { it.balance }
            } catch (_: Exception) {
            }
        }
    }

    private inline fun <T> cardRead(fallback: T, read: () -> T): T =
        try { read() } catch (_: Exception) { fallback }

    private fun rememberCardPinEpoch(accountId: String) {
        val epoch = cardRead(-1) { card.pinEpoch }
        if (epoch >= 0) preferences.setKnownPinEpoch(accountId, epoch)
    }

    private suspend fun adoptCardPin(accountId: String, pin: String): Boolean = try {
        val pinBytes = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())
        if (card.verifyPin(pinBytes)) {
            accounts.changePin(accountId, pin)
            rememberCardPinEpoch(accountId)
            withContext(Dispatchers.Main) { isCardPinOutOfSync = false }
            true
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }

    private fun List<DisplayTransaction>.flagUnsynced(ids: Set<String>) =
        filter { it.tx.id in ids }.map { it.copy(isUnsynced = true) }

    private fun unsyncedIdsFromStore(txList: List<Transaction>): Set<String> {
        val id = currentId ?: return emptySet()
        val lastReceived = cardSyncState.getLastReceived(id)
        return unsyncedIncoming(txList, id, lastReceived).map { it.id }.toSet()
    }

    private suspend fun pushToCard(service: WalletService, txList: List<Transaction>): Set<String>? =
        withContext(Dispatchers.IO) {
            try {
                service.syncIncomingWithCard(txList)
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun readCardSeq(): Long? {
        val service = walletService ?: return null
        val state = withContext(Dispatchers.IO) { service.cardState() } ?: return null
        cardBalance = state.balance
        return state.seq
    }

    private fun markAttempt(id: String, cardSeq: Long) {
        pendingActions = pendingActions.map {
            if (it.id == id) it.copy(attemptedAtCardSeq = cardSeq) else it
        }
        savePending()
    }

    private fun syncIncomingNow() {
        val service = walletService ?: return
        viewModelScope.launch {
            val ids = pushToCard(service, _fullHistory.map { it.tx }) ?: return@launch
            _unsyncedTransactions = _fullHistory.flagUnsynced(ids)
            readCardSeq()
            if (pendingActions.isEmpty()) card.completeSession()
        }
    }

    private fun startNetworkObserver() {
        if (currentId != null) return
        CoreWrapper.initLedger(observerListener, storagePath, "", "")
    }

    private fun restartLedgerListener() {
        val id = currentId
        if (id == null) {
            CoreWrapper.initLedger(observerListener, storagePath, "", "")
            return
        }
        CoreWrapper.initLedger(rustListener, storagePath, id, sessionPublicKeyHex ?: "")
    }

    init {
        startCardPolling()
        startNetworkObserver()
    }

    fun onNetworkPermissionsGranted() = developer.onPermissionsGranted()

    fun stopNetworkServices() = developer.stop()

    private fun clearLedgerState() {
        _fullHistory.clear()
        sessionTransactions.clear()
        _unsyncedTransactions = emptyList()
        globalHistory = emptyList()
        networkAccounts = emptyList()
        knownNetworkPeers = emptyList()
        balance = 0L
        isFirstSync = true
        CoreWrapper.shutdown()
        restartLedgerListener()
    }

    fun selectAccountToLogin(acc: StoredAccount) {
        detectedAccount = acc
        currentScreen = AppScreenState.LOGIN
        errorMessage = null
    }

    fun attemptLogin(pin: String) {
        val acc = detectedAccount ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cardPresent = isCardConnected && currentDetectedCardId == acc.id
                val pinAccepted = if (isCardPinOutOfSync && cardPresent) {
                    adoptCardPin(acc.id, pin)
                } else {
                    accounts.verifyPin(acc.id, pin)
                }

                if (pinAccepted) {
                    sessionPin = pin

                    pendingActions = cardSyncState.getPendingActions(acc.id)

                    var fullPubKeyHex: String? = null
                    if (cardPresent) {
                        fullPubKeyHex = try {
                            card.publicKey?.let { CoreWrapper.bytesToHex(it) }
                        } catch (_: Exception) { null }
                        rememberCardPinEpoch(acc.id)
                    }

                    withContext(Dispatchers.Main) {
                        currentId = acc.id
                        currentName = acc.name
                        isMinter = accounts.isMinter(acc.id)
                        errorMessage = null
                        currentScreen = AppScreenState.DASHBOARD

                        ScreenCaptureProtection.setBlocked(preferences.isScreenCaptureBlocked(acc.id))

                        if (isCardConnected && currentDetectedCardId == acc.id) {
                            walletService = WalletService(card, pin, cardSyncState)
                            processSyncQueue()
                        }

                        sessionPublicKeyHex = fullPubKeyHex
                        CoreWrapper.initLedger(rustListener, storagePath, acc.id, fullPubKeyHex ?: "")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        errorMessage = if (isCardPinOutOfSync) {
                            "This card's PIN was changed on another device. Enter the current card PIN."
                        } else {
                            "Invalid PIN"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = e.message ?: "Login failed" }
            }
        }
    }

    fun logout() {
        walletService?.close()
        walletService = null
        CoreWrapper.shutdown()
        ScreenCaptureProtection.setBlocked(false)
        currentId = null
        sessionPin = null
        sessionPublicKeyHex = null
        currentScreen = AppScreenState.HOME
        pendingActions = emptyList()
        _fullHistory.clear()
        sessionTransactions.clear()
        isFirstSync = true
        history.reset()
        startNetworkObserver()
    }

    fun send(targetId: String, amount: Long) = executeAction(PendingAction(type = "SEND", amount = amount, targetId = targetId))
    fun mint(amount: Long) = executeAction(PendingAction(type = "MINT", amount = amount))
    fun burn(amount: Long) = executeAction(PendingAction(type = "BURN", amount = amount))

    private fun executeAction(action: PendingAction) {
        pendingActions = pendingActions + action
        currentId?.let { cardSyncState.savePendingActions(it, pendingActions) }

        if (walletService != null) processSyncQueue()
    }

    private fun savePending() {
        currentId?.let { cardSyncState.savePendingActions(it, pendingActions) }
    }

    private fun dropPending(id: String) {
        pendingActions = pendingActions.filter { it.id != id }
        savePending()
    }

    fun cancelPendingAction(id: String): Boolean {
        if (processingActionId == id) return false
        dropPending(id)
        return true
    }

    fun updatePendingAction(id: String, amount: Long, targetId: String?): Boolean {
        if (processingActionId == id) return false
        pendingActions = pendingActions.map {
            if (it.id == id) it.copy(amount = amount, targetId = targetId) else it
        }
        savePending()
        return true
    }

    private fun runAction(ws: WalletService, action: PendingAction): String? = try {
        when (action.type) {
            "SEND" -> ws.send(action.targetId!!, action.amount, _fullHistory.map { it.tx })
            "MINT" -> ws.mint(action.amount)
            "BURN" -> ws.burn(action.amount)
        }
        null
    } catch (e: Exception) {
        e.message ?: e.typeName()
    }

    private fun processSyncQueue() {
        if (walletService == null || pendingActions.isEmpty()) return

        viewModelScope.launch {
            queueMutex.withLock {
                while (true) {
                    val ws = walletService ?: break
                    val action = pendingActions.firstOrNull() ?: break

                    val cardSeq = readCardSeq()
                    if (cardSeq == null) {
                        syncStatus = null
                        return@withLock
                    }

                    val attempted = action.attemptedAtCardSeq
                    if (attempted != null && cardSeq > attempted) {
                        dropPending(action.id)
                        errorMessage = "${action.type} reached the card but could not be recorded."
                        continue
                    }

                    markAttempt(action.id, cardSeq)
                    processingActionId = action.id
                    syncStatus = "Processing..."

                    val error = withContext(Dispatchers.IO) { runAction(ws, action) }
                    processingActionId = null

                    if (error != null) {
                        syncStatus = null
                        return@withLock
                    }

                    dropPending(action.id)
                    delay(200.milliseconds)
                }
                syncStatus = null
                readCardSeq()
                if (pendingActions.isEmpty()) card.completeSession()
            }
        }
    }

    fun goToSetup() {
        setupTargetCardId = currentDetectedCardId
        currentScreen = AppScreenState.SETUP
        errorMessage = null
        isSetupLoading = false
        isSetupSuccessful = false
    }

    fun handleSetupOrImportSubmit(pin: String, name: String) {
        if (newCardHasPin) {
            importExistingCard(pin, name)
        } else {
            setupBrandNewCard(pin, name)
        }
    }

    private fun setupBrandNewCard(pin: String, name: String) {
        isSetupLoading = true
        errorMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!card.isConnected) card.connect()

                val retries = try { card.pinRetries } catch(_: Exception) { 3 }
                if (retries == 0) throw Exception("Card is bricked!")

                val pinB = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())

                if (!card.isPinSet) {
                    if (!card.changePin(pinB)) throw Exception("Failed to set PIN")
                }
                if (!card.verifyPin(pinB)) throw Exception("Failed to verify newly set PIN")

                val verified = verifyAndStoreCardAccount(name, pin)

                if (!card.isGenesisDone) {
                    try {
                        val response = card.processGenesis()
                        val sigBytes = ProtocolSerializer.parseGenesisSignatureFromResponse(response)

                        val sigHex = CoreWrapper.bytesToHex(sigBytes)
                        val certHex = CoreWrapper.bytesToHex(verified.certificate)

                        CoreWrapper.genesis(sigHex, certHex)
                    } catch (_: Exception) {
                    }
                }

                finishCardSetup(verified.idHex, pin)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSetupLoading = false
                    errorMessage = "Setup Error: ${e.typeName()} - ${e.message}"
                }
            }
        }
    }

    private fun importExistingCard(pin: String, name: String) {
        isSetupLoading = true
        errorMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!card.isConnected) card.connect()

                val pinB = ProtocolSerializer.validateAndConvertPin(pin.toCharArray())

                if (!card.verifyPin(pinB)) {
                    val left = try { card.pinRetries } catch (_: Exception) { 0 }
                    throw Exception(if (left == 0) "Card is bricked!" else "Wrong PIN. $left attempts remaining.")
                }

                val verified = verifyAndStoreCardAccount(name, pin)
                finishCardSetup(verified.idHex, pin)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSetupLoading = false
                    errorMessage = "Setup Error: ${e.typeName()} - ${e.message}"
                }
            }
        }
    }

    private class VerifiedCard(val idHex: String, val certificate: ByteArray)

    private suspend fun verifyAndStoreCardAccount(name: String, pin: String): VerifiedCard {
        val pubKey = card.publicKey ?: throw Exception("Missing Public Key")
        val cert = card.certificate ?: throw Exception("Missing Certificate.")

        if (!CoreWrapper.verifyCardCertificate(pubKey, cert)) {
            throw Exception("Security Alert: Invalid Card Certificate!")
        }

        val idHex = CoreWrapper.getPersonIdAsHex(pubKey)
        val fullPubKeyHex = CoreWrapper.bytesToHex(pubKey)
        accounts.createAccount(idHex, name, pin)

        val isCardMinter = try { card.isMinter } catch(_: Exception) { false }
        accounts.setMinterStatus(idHex, isCardMinter)
        rememberCardPinEpoch(idHex)

        withContext(Dispatchers.Main) {
            CoreWrapper.initLedger(rustListener, storagePath, idHex, fullPubKeyHex)
        }
        return VerifiedCard(idHex, cert)
    }

    private suspend fun finishCardSetup(idHex: String, pin: String) = withContext(Dispatchers.Main) {
        availableAccounts = accounts.getAllAccounts()
        isSetupLoading = false
        isSetupSuccessful = true
        tempSetupAccount = accounts.getAccount(idHex)
        tempSetupPin = pin
        physicallyConnectedCardAccount = tempSetupAccount
        isNewCardDetected = false
        card.completeSession()
    }

    fun completeSetup(wantsBiometrics: Boolean = false) {
        if (tempSetupAccount != null && tempSetupPin != null) {
            val accountToLogin = tempSetupAccount!!
            detectedAccount = accountToLogin

            preferences.setBiometricsEnabled(accountToLogin.id, wantsBiometrics)

            attemptLogin(tempSetupPin!!)
        } else {
            currentScreen = AppScreenState.HOME
        }
        isSetupSuccessful = false
        tempSetupAccount = null
        tempSetupPin = null
    }

    fun goToSettings() { currentScreen = AppScreenState.SETTINGS }
    
    private var relockAccount: StoredAccount? = null

    fun onAppBackground() {
        if (currentId != null &&
            (currentScreen == AppScreenState.DASHBOARD || currentScreen == AppScreenState.SETTINGS)
        ) {
            relockAccount = availableAccounts.find { it.id == currentId }
            logout()
        }
    }

    fun onAppForeground() {
        relockAccount?.let { selectAccountToLogin(it) }
        relockAccount = null
    }

    var isBenchmarkMode by mutableStateOf(false); private set

    fun enableBenchmarkMode(enabled: Boolean) {
        isBenchmarkMode = enabled
        CoreWrapper.benchSetMode(enabled)
        if (enabled) {
            stopCardPolling()
        } else {
            startCardPolling()
        }
        developer.onPermissionsGranted()
    }

    fun runBenchmark(kind: BenchmarkKind) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = when (kind) {
                BenchmarkKind.WORKLOAD -> {
                    val count = CoreWrapper.benchGenerateWorkload(storagePath)
                    "Workload ready: $count transactions."
                }
                BenchmarkKind.STORE -> {
                    val count = CoreWrapper.benchRunStore(storagePath)
                    "Local storage measured over $count transactions."
                }
                BenchmarkKind.LATENCY -> {
                    if (CoreWrapper.benchRunLatency(storagePath)) {
                        "Latency commit sent."
                    } else {
                        "Latency needs a workload."
                    }
                }
            }
            withContext(Dispatchers.Main) {
                showUserMessage(message)
            }
        }
    }

    fun isScreenCaptureBlocked(): Boolean {
        val id = currentId ?: return false
        return preferences.isScreenCaptureBlocked(id)
    }

    fun setScreenCaptureBlocked(blocked: Boolean) {
        val id = currentId ?: return
        preferences.setScreenCaptureBlocked(id, blocked)
        ScreenCaptureProtection.setBlocked(blocked)
    }

    fun isBiometricsEnabled(accountId: String) = preferences.isBiometricsEnabled(accountId)

    fun setBiometricsEnabled(accountId: String, enabled: Boolean) = preferences.setBiometricsEnabled(accountId, enabled)

    fun changeCardPin(newPin: String, onSuccess: () -> Unit, onError: () -> Unit) {
        isSettingsLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = currentId ?: throw IllegalStateException("No active account")
                val activePin = sessionPin ?: throw IllegalStateException("Session PIN unavailable")

                if (!card.probe()) throw IllegalStateException("Hold the card to your device to change its PIN")

                val currentPinBytes = ProtocolSerializer.validateAndConvertPin(activePin.toCharArray())
                if (!card.verifyPin(currentPinBytes)) {
                    val left = try { card.pinRetries } catch (_: Exception) { -1 }
                    throw IllegalStateException(
                        if (left >= 0) "Card rejected the current PIN. $left attempts remaining." else "Card rejected the current PIN."
                    )
                }

                val newPinBytes = ProtocolSerializer.validateAndConvertPin(newPin.toCharArray())
                if (!card.changePin(newPinBytes)) throw IllegalStateException("Card rejected the new PIN")

                accounts.changePin(id, newPin)
                rememberCardPinEpoch(id)

                withContext(Dispatchers.Main) {
                    sessionPin = newPin
                    isCardPinOutOfSync = false
                    walletService = WalletService(card, newPin, cardSyncState)
                    card.completeSession()
                    isSettingsLoading = false
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSettingsLoading = false
                    errorMessage = e.message ?: "Could not change the card PIN"
                    onError()
                }
            }
        }
    }

    fun deleteCurrentAccount() {
        currentId?.let { uid ->
            viewModelScope.launch(Dispatchers.IO) {
                accounts.deleteAccount(uid)
                withContext(Dispatchers.Main) {
                    availableAccounts = accounts.getAllAccounts()
                    logout()
                }
            }
        }
    }

    fun updateAccountName(newName: String) {
        currentId?.let { uid ->
            viewModelScope.launch(Dispatchers.IO) {
                accounts.updateAccountName(uid, newName)
                withContext(Dispatchers.Main) {
                    currentName = newName
                    availableAccounts = accounts.getAllAccounts()
                }
            }
        }
    }

    fun showUserMessage(msg: String) {
        userMessage = msg
        viewModelScope.launch { delay(3000.milliseconds); if (userMessage == msg) userMessage = null }
    }

    fun dismissUserMessage() { userMessage = null }
    fun dismissError() { errorMessage = null }
    fun getUnsyncedIncoming(): List<DisplayTransaction> = _unsyncedTransactions

    fun getAccountName(id: String): String? = availableAccounts.find { it.id == id }?.name

    fun getPeerName(id: String): String? {
        return availableAccounts.find { it.id == id }?.name ?: knownNetworkPeers.find { it.id == id }?.label
    }

    private fun stopCardPolling() {
        cardPollingJob?.cancel()
        cardPollingJob = null
    }

    private fun startCardPolling() {
        cardPollingJob?.cancel()
        cardPollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!isSetupLoading) {
                        if (!card.probe()) throw Exception("Card not reachable")
                        if (cardRead(3) { card.pinRetries } == 0) throw Exception("Bricked")

                        val pubKey = card.publicKey ?: throw Exception()
                        val idHex = CoreWrapper.getPersonIdAsHex(pubKey)
                        val account = accounts.getAccount(idHex)
                        val isPinSet = cardRead(false) { card.isPinSet }
                        val epoch = cardRead(-1) { card.pinEpoch }

                        withContext(Dispatchers.Main) {
                            isCardConnected = true
                            currentDetectedCardId = idHex
                            physicallyConnectedCardAccount = account
                            isNewCardDetected = account == null
                            newCardHasPin = isPinSet

                            if (account != null && epoch >= 0) {
                                val known = preferences.getKnownPinEpoch(account.id)
                                isCardPinOutOfSync = known >= 0 && known != epoch
                            }

                            if (currentScreen == AppScreenState.DASHBOARD && currentId == idHex && walletService == null && sessionPin != null) {
                                walletService = WalletService(card, sessionPin!!, cardSyncState)
                                processSyncQueue()
                                syncIncomingNow()
                            }
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        if (isCardConnected) {
                            isCardConnected = false
                            cardBalance = null
                            walletService?.close()
                            walletService = null
                        }
                    }
                    try { card.disconnect() } catch (_: Exception) {}
                }
                delay(1500.milliseconds)
            }
        }
    }

    fun cancelAuth() { logout() }
}
