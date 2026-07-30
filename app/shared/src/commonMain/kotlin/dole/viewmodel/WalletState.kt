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

data class NetworkAccountSummary(
    val id: String,
    val name: String?,
    val balance: Long,
    val txCount: Int
)

@Serializable
data class PendingAction(
    val id: String = kotlin.random.Random.nextLong().toString(),
    val type: String,
    val amount: Long,
    val targetId: String? = null,
    val attemptedAtCardSeq: Long? = null
)

data class PeerConnection(
    val sessionId: String,
    val ble: Boolean,
    val mdns: Boolean,
    val internet: Boolean
)

data class TransportStatus(
    val ble: Boolean,
    val iroh: Boolean,
    val internet: Boolean
)

enum class BenchmarkKind { WORKLOAD, STORE, LATENCY }
