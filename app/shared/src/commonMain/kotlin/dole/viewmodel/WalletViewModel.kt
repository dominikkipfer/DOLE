package dole.viewmodel

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dole.Constants
import dole.card.SmartCard
import dole.core.CoreWrapper
import dole.core.UIStateListener
import dole.data.AccountRepository
import dole.data.AccountStorage
import dole.data.models.BurnTransaction
import dole.data.models.GenesisTransaction
import dole.data.models.MintTransaction
import dole.data.models.SendTransaction
import dole.data.models.StoredAccount
import dole.data.models.Transaction
import dole.utils.ProtocolSerializer
import dole.wallet.WalletService
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

class WalletViewModel(
    private val accountRepo: AccountRepository,
    private val storage: AccountStorage,
    private val card: SmartCard,
    private val storagePath: String
) {
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var walletService: WalletService? = null
    private var sessionPin: String? = null

    var currentScreen by mutableStateOf(AppScreenState.HOME)
    var availableAccounts by mutableStateOf(accountRepo.getAllAccounts()); private set
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
    var isSearchMode by mutableStateOf(false); private set
    var filterTypes = mutableStateListOf<TxFilterType>()
    var filterPeerQuery by mutableStateOf("")
    var sortField by mutableStateOf(SortField.NONE)
    var sortAscending by mutableStateOf(false)

    val isBalancePending by derivedStateOf { pendingActions.isNotEmpty() || _unsyncedTransactions.isNotEmpty() }

    val filteredHistory by derivedStateOf {
        val rawQuery = filterPeerQuery.trim().lowercase()
        val selectedTypes = if (filterTypes.isEmpty()) TxFilterType.entries.toSet() else filterTypes.toSet()
        val hasSendOrReceive = selectedTypes.contains(TxFilterType.SEND) || selectedTypes.contains(TxFilterType.RECEIVE)
        val shouldCheckPeer = rawQuery.isNotEmpty() && hasSendOrReceive

        val pendingAsDisplay = pendingActions.map { action ->
            val dummyId = "pending-${action.id}"
            val tx: Transaction = when (action.type) {
                "MINT" -> MintTransaction(id = dummyId, author = currentId ?: "", timestamp = 0L, seq = 0L, signature = "", goc = action.amount)
                "BURN" -> BurnTransaction(id = dummyId, author = currentId ?: "", timestamp = 0L, seq = 0L, signature = "", goc = action.amount)
                "SEND" -> SendTransaction(id = dummyId, author = currentId ?: "", timestamp = 0L, seq = 0L, signature = "", target = action.targetId ?: "?", goc = action.amount)
                else -> MintTransaction(id = dummyId, author = "", timestamp = 0L, seq = 0L, signature = "", goc = 0L)
            }
            DisplayTransaction(tx, action.amount, isUnsynced = true)
        }

        val combinedList = _fullHistory + pendingAsDisplay + _unsyncedTransactions.filter { unsynced ->
            _fullHistory.none { it.tx.id == unsynced.tx.id }
        }

        val uniqueList = combinedList.distinctBy { it.tx.id }
        val filtered = uniqueList.filter { item ->
            val tx = item.tx
            val isMe = tx.author == currentId
            val typeEnum = when (tx) {
                is MintTransaction -> TxFilterType.MINT
                is BurnTransaction -> TxFilterType.BURN
                is SendTransaction -> if (isMe) TxFilterType.SEND else TxFilterType.RECEIVE
                else -> null
            }
            if (typeEnum == null || !selectedTypes.contains(typeEnum)) return@filter false

            if (shouldCheckPeer) {
                val peerId = when (tx) {
                    is SendTransaction -> if (isMe) tx.target else tx.author
                    else -> tx.author
                }
                val peerName = getPeerName(peerId)?.lowercase() ?: ""
                if (!peerId.lowercase().contains(rawQuery) && !peerName.contains(rawQuery)) return@filter false
            }
            true
        }

        when (sortField) {
            SortField.AMOUNT -> {
                if (sortAscending) filtered.sortedBy { getSignedAmount(it) }
                else filtered.sortedByDescending { getSignedAmount(it) }
            }
            SortField.TYPE -> {
                if (sortAscending) filtered.sortedBy { getSortableType(it) }
                else filtered.sortedByDescending { getSortableType(it) }
            }
            SortField.NONE -> {
                filtered.sortedWith(compareByDescending<DisplayTransaction> {
                    if (it.isUnsynced) Long.MAX_VALUE else it.tx.timestamp
                }.thenByDescending { it.tx.seq })
            }
        }
    }

    private var cardPollingJob: Job? = null

    private val rustListener = object : UIStateListener {
        override fun onStateUpdated(balance: Long, historyJson: String) {
            viewModelScope.launch {
                this@WalletViewModel.balance = balance
                try {
                    val dtoList = Json.decodeFromString<List<RustTxDto>>(historyJson)
                    val txList = mutableListOf<Transaction>()
                    val displayList = mutableListOf<DisplayTransaction>()

                    for (dto in dtoList) {
                        val tx: Transaction? = when (dto.type) {
                            "MINT" -> MintTransaction(
                                id = dto.id,
                                author = dto.author,
                                timestamp = dto.timestamp,
                                seq = dto.seq,
                                signature = dto.signature,
                                goc = dto.goc
                            )
                            "BURN" -> BurnTransaction(
                                id = dto.id,
                                author = dto.author,
                                timestamp = dto.timestamp,
                                seq = dto.seq,
                                signature = dto.signature,
                                goc = dto.goc
                            )
                            "SEND" -> SendTransaction(
                                id = dto.id,
                                author = dto.author,
                                timestamp = dto.timestamp,
                                seq = dto.seq,
                                signature = dto.signature,
                                target = dto.target,
                                goc = dto.goc
                            )
                            "GENESIS" -> GenesisTransaction(
                                id = dto.id,
                                author = dto.author,
                                timestamp = dto.timestamp,
                                seq = dto.seq,
                                signature = dto.signature,
                                publicKey = dto.publicKey ?: dto.author,
                                attachmentCertificate = dto.certificate?.let { CoreWrapper.hexToBytes(it) }
                            )
                            else -> null
                        }

                        if (tx != null) {
                            txList.add(tx)
                            displayList.add(DisplayTransaction(tx, dto.goc, false))
                        }
                    }

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
                        val name = accountRepo.getAccount(peerId)?.name ?: "User ...${peerId.takeLast(6)}"
                        PeerOption(peerId, name)
                    }

                    walletService?.syncIncomingWithCard(txList)
                } catch (e: Exception) {
                    println("JSON Parse Error: ${e.message}")
                }
            }
        }

        override fun onError(message: String) {
            viewModelScope.launch {
                errorMessage = message
            }
        }
    }

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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (accountRepo.verifyPin(acc.id, pin)) {
                    sessionPin = pin

                    pendingActions = storage.getPendingActions(acc.id)

                    var fullPubKeyHex: String? = null
                    if (isCardConnected && currentDetectedCardId == acc.id) {
                        fullPubKeyHex = try {
                            card.publicKey?.let { CoreWrapper.bytesToHex(it) }
                        } catch (_: Exception) { null }
                    }

                    withContext(Dispatchers.Main) {
                        currentId = acc.id
                        currentName = acc.name
                        isMinter = accountRepo.isMinter(acc.id)
                        errorMessage = null
                        currentScreen = AppScreenState.DASHBOARD

                        if (isCardConnected && currentDetectedCardId == acc.id) {
                            walletService = WalletService(card, pin, storage)
                            processSyncQueue()
                        }

                        CoreWrapper.initLedger(rustListener, storagePath, acc.id, fullPubKeyHex ?: "")
                    }
                } else {
                    withContext(Dispatchers.Main) { errorMessage = "Invalid PIN" }
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
        currentId = null
        sessionPin = null
        currentScreen = AppScreenState.HOME
        pendingActions = emptyList()
        _fullHistory.clear()
        sessionTransactions.clear()
        isFirstSync = true
        isSearchMode = false
        filterTypes.clear()
        filterPeerQuery = ""
    }

    fun send(targetId: String, amount: Long) = executeAction(PendingAction(type = "SEND", amount = amount, targetId = targetId))
    fun mint(amount: Long) = executeAction(PendingAction(type = "MINT", amount = amount))
    fun burn(amount: Long) = executeAction(PendingAction(type = "BURN", amount = amount))

    private fun executeAction(action: PendingAction) {
        pendingActions = pendingActions + action
        currentId?.let { storage.savePendingActions(it, pendingActions) }

        if (walletService != null) processSyncQueue()
    }

    private fun processSyncQueue() {
        val ws = walletService ?: return
        if (pendingActions.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val toProcess = pendingActions.toList()
            for (action in toProcess) {
                try {
                    withContext(Dispatchers.Main) { syncStatus = "Processing..." }
                    when (action.type) {
                        "SEND" -> ws.send(action.targetId!!, action.amount, _fullHistory.map { it.tx })
                        "MINT" -> ws.mint(action.amount)
                        "BURN" -> ws.burn(action.amount)
                    }
                    withContext(Dispatchers.Main) {
                        pendingActions = pendingActions.filter { it.id != action.id }
                        currentId?.let { storage.savePendingActions(it, pendingActions) }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Transaction failed: ${e.message}"
                        pendingActions = pendingActions.filter { it.id != action.id }
                        currentId?.let { storage.savePendingActions(it, pendingActions) }
                    }
                }
                delay(200.milliseconds)
            }
            withContext(Dispatchers.Main) { syncStatus = null }
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

                val pubKey = card.publicKey ?: throw Exception("Missing Public Key")
                val cert = card.certificate ?: throw Exception("Missing Certificate.")

                if (!CoreWrapper.verifyCardCertificate(pubKey, cert)) {
                    throw Exception("Security Alert: Invalid Card Certificate!")
                }

                val idHex = CoreWrapper.getPersonIdAsHex(pubKey)
                val fullPubKeyHex = CoreWrapper.bytesToHex(pubKey)
                accountRepo.createAccount(idHex, name, pin)

                val isCardMinter = try { card.isMinter } catch(_: Exception) { false }
                accountRepo.setMinterStatus(idHex, isCardMinter)

                withContext(Dispatchers.Main) {
                    CoreWrapper.initLedger(rustListener, storagePath, idHex, fullPubKeyHex)
                }

                if (!card.isGenesisDone) {
                    try {
                        val response = card.processGenesis()
                        val sigBytes = response.copyOfRange(Constants.LONG_SIZE.toInt(), response.size)

                        val sigHex = CoreWrapper.bytesToHex(sigBytes)
                        val certHex = CoreWrapper.bytesToHex(cert)

                        CoreWrapper.genesis(fullPubKeyHex, sigHex, certHex)
                    } catch (e: Exception) {
                        println("Genesis failed: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    availableAccounts = accountRepo.getAllAccounts()
                    isSetupLoading = false
                    isSetupSuccessful = true
                    tempSetupAccount = accountRepo.getAccount(idHex)
                    tempSetupPin = pin
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSetupLoading = false
                    errorMessage = "Setup Error: ${e.javaClass.simpleName} - ${e.message}"
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

                val pubKey = card.publicKey ?: throw Exception("Missing Public Key")
                val cert = card.certificate ?: throw Exception("Missing Certificate.")

                if (!CoreWrapper.verifyCardCertificate(pubKey, cert)) {
                    throw Exception("Security Alert: Invalid Card Certificate!")
                }

                val idHex = CoreWrapper.getPersonIdAsHex(pubKey)
                val fullPubKeyHex = CoreWrapper.bytesToHex(pubKey)

                accountRepo.createAccount(idHex, name, pin)

                val isCardMinter = try { card.isMinter } catch(_: Exception) { false }
                accountRepo.setMinterStatus(idHex, isCardMinter)

                withContext(Dispatchers.Main) {
                    CoreWrapper.initLedger(rustListener, storagePath, idHex, fullPubKeyHex)

                    availableAccounts = accountRepo.getAllAccounts()
                    isSetupLoading = false
                    isSetupSuccessful = true
                    tempSetupAccount = accountRepo.getAccount(idHex)
                    tempSetupPin = pin
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSetupLoading = false
                    errorMessage = "Setup Error: ${e.javaClass.simpleName} - ${e.message}"
                }
            }
        }
    }

    fun completeSetup(wantsBiometrics: Boolean = false) {
        if (tempSetupAccount != null && tempSetupPin != null) {
            val accountToLogin = tempSetupAccount!!
            detectedAccount = accountToLogin

            storage.setBiometricsEnabled(accountToLogin.id, wantsBiometrics)

            attemptLogin(tempSetupPin!!)
        } else {
            currentScreen = AppScreenState.HOME
        }
        isSetupSuccessful = false
        tempSetupAccount = null
        tempSetupPin = null
    }

    fun goToSettings() { currentScreen = AppScreenState.SETTINGS }

    fun isBiometricsEnabled(accountId: String) = storage.isBiometricsEnabled(accountId)

    fun setBiometricsEnabled(accountId: String, enabled: Boolean) = storage.setBiometricsEnabled(accountId, enabled)

    fun changeCardPin(newPin: String, onSuccess: () -> Unit, onError: () -> Unit) {
        isSettingsLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                currentId?.let { accountRepo.changePin(it, newPin) }
                withContext(Dispatchers.Main) { isSettingsLoading = false; onSuccess() }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { isSettingsLoading = false; onError() }
            }
        }
    }

    fun deleteCurrentAccount() {
        currentId?.let { uid ->
            viewModelScope.launch(Dispatchers.IO) {
                accountRepo.deleteAccount(uid)
                withContext(Dispatchers.Main) {
                    availableAccounts = accountRepo.getAllAccounts()
                    logout()
                }
            }
        }
    }

    fun updateAccountName(newName: String) {
        currentId?.let { uid ->
            viewModelScope.launch(Dispatchers.IO) {
                accountRepo.updateAccountName(uid, newName)
                withContext(Dispatchers.Main) {
                    currentName = newName
                    availableAccounts = accountRepo.getAllAccounts()
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

    fun getPeerName(id: String): String? {
        return availableAccounts.find { it.id == id }?.name ?: knownNetworkPeers.find { it.id == id }?.label
    }

    fun toggleSearchMode() { isSearchMode = !isSearchMode }

    fun toggleFilterType(type: TxFilterType) {
        if (filterTypes.contains(type)) filterTypes.remove(type) else filterTypes.add(type)
    }

    fun cycleSort(field: SortField) {
        if (sortField == field) {
            if (!sortAscending) sortAscending = true
            else { sortField = SortField.NONE; sortAscending = false }
        } else {
            sortField = field
            sortAscending = false
        }
    }

    private fun getSignedAmount(item: DisplayTransaction): Long {
        val tx = item.tx
        val isMe = tx.author == currentId
        return when (tx) {
            is BurnTransaction -> -item.delta
            is SendTransaction -> if (isMe) -item.delta else item.delta
            else -> item.delta
        }
    }

    private fun getSortableType(item: DisplayTransaction): Int {
        val tx = item.tx
        val isMe = tx.author == currentId
        return when (tx) {
            is MintTransaction -> 1
            is BurnTransaction -> 2
            is SendTransaction -> if (isMe) 3 else 4
            else -> 4
        }
    }

    private fun startCardPolling() {
        cardPollingJob?.cancel()
        cardPollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!isSetupLoading) {
                        if (!card.isConnected) card.connect()
                        val retries = try { card.pinRetries } catch(_:Exception) { 3 }
                        if (retries == 0) throw Exception("Bricked")

                        val pubKey = card.publicKey ?: throw Exception()
                        val idHex = CoreWrapper.getPersonIdAsHex(pubKey)
                        val account = accountRepo.getAccount(idHex)
                        val isPinSet = try { card.isPinSet } catch (_: Exception) { false }

                        withContext(Dispatchers.Main) {
                            isCardConnected = true
                            currentDetectedCardId = idHex
                            physicallyConnectedCardAccount = account
                            isNewCardDetected = account == null
                            newCardHasPin = isPinSet

                            if (currentScreen == AppScreenState.DASHBOARD && currentId == idHex && walletService == null && sessionPin != null) {
                                walletService = WalletService(card, sessionPin!!, storage)
                                processSyncQueue()
                            }
                        }
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) {
                        if (isCardConnected) {
                            isCardConnected = false
                            walletService?.close()
                            walletService = null
                        }
                        currentDetectedCardId = null
                        physicallyConnectedCardAccount = null
                        isNewCardDetected = false
                    }
                    try { card.disconnect() } catch (_: Exception) {}
                }
                delay(1500.milliseconds)
            }
        }
    }

    fun cancelAuth() { logout() }
}
