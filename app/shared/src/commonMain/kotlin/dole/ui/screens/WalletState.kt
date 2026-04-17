package dole.ui.screens

import dole.data.models.Transaction

enum class AppScreenState { HOME, LOGIN, SETUP, DASHBOARD, SETTINGS }
enum class TxFilterType { SEND, RECEIVE, MINT, BURN }
enum class SortField { NONE, TYPE, AMOUNT }

data class DisplayTransaction(
    val tx: Transaction,
    val delta: Int,
    val isUnsynced: Boolean = false
)

data class PeerOption(
    val id: String,
    val label: String
)