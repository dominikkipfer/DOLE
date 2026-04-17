package dole.ui.screens

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dole.core.CoreWrapper
import dole.core.UIStateListener
import dole.data.models.BurnTransaction
import dole.data.models.MintTransaction
import dole.data.models.SendTransaction
import dole.data.models.StoredAccount
import dole.data.models.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PendingAction(
    val id: String = kotlin.random.Random.nextLong().toString(),
    val type: String,
    val amount: Int,
    val targetId: String? = null
)

class WalletViewModel(private val storagePath: String) {
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var errorMessage by mutableStateOf<String?>(null); private set
    var userMessage by mutableStateOf<String?>(null); private set

    var currentScreen by mutableStateOf(AppScreenState.DASHBOARD)

    var availableAccounts by mutableStateOf(listOf(StoredAccount("PubKey_12345ABC", "Prototyp User", "")))
        private set

    var detectedAccount by mutableStateOf<StoredAccount?>(null)
    var physicallyConnectedCardAccount by mutableStateOf<StoredAccount?>(null); private set
    var isNewCardDetected by mutableStateOf(false); private set
    var currentDetectedCardId by mutableStateOf<String?>(null); private set
    var isCardConnected by mutableStateOf(true); private set
    var newCardHasPin by mutableStateOf(false); private set

    var isSetupLoading by mutableStateOf(false); private set
    var isSetupSuccessful by mutableStateOf(false); private set
    var setupTargetCardId by mutableStateOf<String?>(null); private set

    var balance by mutableIntStateOf(0); private set

    var currentId by mutableStateOf<String?>("PubKey_12345ABC"); private set
    var isMinter by mutableStateOf(true); private set

    var pendingActions by mutableStateOf<List<PendingAction>>(emptyList()); private set
    val sessionTransactions = mutableStateListOf<DisplayTransaction>()
    var knownNetworkPeers by mutableStateOf<List<PeerOption>>(emptyList()); private set

    var isSearchMode by mutableStateOf(false); private set
    var filterTypes = mutableStateListOf<TxFilterType>()
    var filterPeerQuery by mutableStateOf("")
    var sortField by mutableStateOf(SortField.NONE)
    var sortAscending by mutableStateOf(false)

    val isBalancePending by derivedStateOf { pendingActions.isNotEmpty() }

    private var _rawHistory = mutableStateListOf<DisplayTransaction>()
    val filteredHistory: List<DisplayTransaction> get() = _rawHistory.toList()

    init {
        startPrototype()
    }

    private fun startPrototype() {
        val listener = object : UIStateListener {
            override fun onStateUpdated(balance: Int, historyJson: String) {
                viewModelScope.launch(Dispatchers.Main) {
                    if (balance == -999) {
                        errorMessage = "RUST: $historyJson"
                        return@launch
                    }

                    this@WalletViewModel.balance = balance
                    parseRustHistory(historyJson)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            CoreWrapper.initPrototype(listener, storagePath)
        }
    }

    fun selectAccountToLogin(acc: StoredAccount) {
        detectedAccount = acc
        currentScreen = AppScreenState.LOGIN
        errorMessage = null
    }

    fun attemptLogin(pin: String) {
        val acc = detectedAccount ?: return
        currentId = acc.id
        currentScreen = AppScreenState.DASHBOARD
    }

    fun logout() {
        currentId = null
        currentScreen = AppScreenState.HOME
    }

    fun cancelAuth() { logout() }
    fun goToSettings() { currentScreen = AppScreenState.SETTINGS }
    fun goToSetup() { currentScreen = AppScreenState.SETUP }

    fun mint(amount: Int) {
        viewModelScope.launch(Dispatchers.IO) { CoreWrapper.mint(amount) }
    }

    fun burn(amount: Int) {
        viewModelScope.launch(Dispatchers.IO) { CoreWrapper.burn(amount) }
    }

    fun send(targetId: String, amount: Int) {
        viewModelScope.launch(Dispatchers.IO) { CoreWrapper.send(targetId, amount) }
    }

    fun dismissError() { errorMessage = null }
    fun dismissUserMessage() { userMessage = null }
    fun showUserMessage(msg: String) { userMessage = msg }

    fun getUnsyncedIncoming(): List<DisplayTransaction> = emptyList()
    fun getPeerName(id: String): String? = availableAccounts.find { it.id == id }?.name

    fun toggleSearchMode() { isSearchMode = !isSearchMode }
    fun toggleFilterType(type: TxFilterType) {
        if (filterTypes.contains(type)) filterTypes.remove(type) else filterTypes.add(type)
    }
    fun cycleSort(field: SortField) {
        if (sortField == field) {
            if (!sortAscending) sortAscending = true
            else { sortField = SortField.NONE; sortAscending = false }
        } else { sortField = field; sortAscending = false }
    }

    fun registerNewCard(pin: String, name: String) {
        isSetupSuccessful = true
        currentScreen = AppScreenState.HOME
    }

    fun completeSetup() {
        isSetupSuccessful = false
        currentScreen = AppScreenState.HOME
    }

    private fun parseRustHistory(jsonStr: String) {
        try {
            _rawHistory.clear()
            val uid = currentId ?: "PubKey_12345ABC"
            val jsonArray = Json.parseToJsonElement(jsonStr).jsonArray

            jsonArray.forEachIndexed { index, element ->
                val obj = element.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: ""
                val goc = obj["goc"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val target = obj["target"]?.jsonPrimitive?.content ?: ""
                val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val txId = "tx_$index"

                val tx: Transaction? = when (type) {
                    "MINT" -> MintTransaction(txId, uid, timestamp, goc)
                    "BURN" -> BurnTransaction(txId, uid, timestamp, goc)
                    "SEND" -> SendTransaction(txId, uid, timestamp, target, goc)
                    else -> null
                }

                if (tx != null) {
                    _rawHistory.add(DisplayTransaction(tx, goc.toInt(), false))
                }
            }
        } catch (_: Exception) { }
    }
}