package dole.data

import dole.ui.screens.DisplayTransaction
import dole.ui.screens.TxFilterType
import dole.ui.screens.SortField

interface LedgerRepository {
    suspend fun getBalance(accountId: String): Int
    suspend fun getHistory(
        accountId: String,
        searchQuery: String = "",
        filterTypes: Set<TxFilterType> = emptySet(),
        sortField: SortField = SortField.NONE,
        sortAscending: Boolean = false
    ): List<DisplayTransaction>
    suspend fun send(accountId: String, targetId: String, amount: Int)
    suspend fun mint(accountId: String, amount: Int)
    suspend fun burn(accountId: String, amount: Int)
    suspend fun sync(accountId: String)
    fun getKnownPeers(accountId: String): List<String>
}