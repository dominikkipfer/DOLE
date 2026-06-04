package dole.viewmodel

import dole.data.models.Transaction
import kotlinx.serialization.Serializable

enum class AppScreenState { HOME, LOGIN, SETUP, DASHBOARD, SETTINGS }
enum class TxFilterType { SEND, RECEIVE, MINT, BURN }
enum class SortField { NONE, TYPE, AMOUNT }

data class DisplayTransaction(
    val tx: Transaction,
    val delta: Long,
    val isUnsynced: Boolean = false
)

data class PeerOption(
    val id: String,
    val label: String
)

@Serializable
data class PendingAction(
    val id: String = kotlin.random.Random.nextLong().toString(),
    val type: String,
    val amount: Long,
    val targetId: String? = null
)

@Serializable
data class RustTxDto(
    val id: String,
    val type: String,
    val goc: Long,
    val author: String,
    val target: String,
    val seq: Long,
    val timestamp: Long,
    val signature: String,
    val certificate: String? = null,
    val publicKey: String? = null
)