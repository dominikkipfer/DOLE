package dole.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dole.data.models.BurnTransaction
import dole.data.models.GenesisTransaction
import dole.data.models.MintTransaction
import dole.data.models.SendTransaction
import dole.viewmodel.DisplayTransaction
import dole.viewmodel.SortField
import dole.viewmodel.TxFilterType

class HistoryFilter {

    var isSearchMode by mutableStateOf(false); private set
    var peerQuery by mutableStateOf("")
    val types = mutableStateListOf<TxFilterType>()
    var sortField by mutableStateOf(SortField.NONE); private set
    var sortAscending by mutableStateOf(false); private set

    fun showAllTransactions(enabled: Boolean) {
        isSearchMode = enabled
    }

    fun toggleType(type: TxFilterType) {
        if (types.contains(type)) types.remove(type) else types.add(type)
    }

    fun cycleSort(field: SortField) {
        if (sortField != field) {
            sortField = field
            sortAscending = false
            return
        }
        if (!sortAscending) {
            sortAscending = true
        } else {
            sortField = SortField.NONE
            sortAscending = false
        }
    }

    fun reset() {
        isSearchMode = false
        peerQuery = ""
        types.clear()
        sortField = SortField.NONE
        sortAscending = false
    }

    fun apply(
        transactions: List<DisplayTransaction>,
        currentId: String?,
        peerName: (String) -> String?
    ): List<DisplayTransaction> {
        val query = peerQuery.trim().lowercase()
        val selected = if (types.isEmpty()) TxFilterType.entries.toSet() else types.toSet()
        val matchesPeer = query.isNotEmpty() && (selected.contains(TxFilterType.SEND) || selected.contains(TxFilterType.RECEIVE))

        val filtered = transactions.filter { item ->
            val type = item.filterType(currentId) ?: return@filter false
            if (!selected.contains(type)) return@filter false
            if (!matchesPeer) return@filter true

            val peerId = item.peerId(currentId)
            peerId.lowercase().contains(query) || peerName(peerId)?.lowercase()?.contains(query) == true
        }

        return when (sortField) {
            SortField.AMOUNT -> filtered.sortedByAscending(sortAscending) { it.signedAmount(currentId) }
            SortField.TYPE -> filtered.sortedByAscending(sortAscending) { it.sortableType(currentId) }
            SortField.NONE -> filtered.sortedWith(
                compareByDescending<DisplayTransaction> {
                    if (it.isUnsynced) Long.MAX_VALUE else it.tx.timestamp
                }.thenByDescending { it.tx.seq }
            )
        }
    }
}

private fun <T : Comparable<T>> List<DisplayTransaction>.sortedByAscending(
    ascending: Boolean,
    selector: (DisplayTransaction) -> T
): List<DisplayTransaction> = if (ascending) sortedBy(selector) else sortedByDescending(selector)

private fun DisplayTransaction.filterType(currentId: String?): TxFilterType? = when (tx) {
    is MintTransaction -> TxFilterType.MINT
    is BurnTransaction -> TxFilterType.BURN
    is SendTransaction -> if (tx.author == currentId) TxFilterType.SEND else TxFilterType.RECEIVE
    is GenesisTransaction -> null
}

private fun DisplayTransaction.peerId(currentId: String?): String = when (tx) {
    is SendTransaction -> if (tx.author == currentId) tx.target else tx.author
    else -> tx.author
}

private fun DisplayTransaction.signedAmount(currentId: String?): Long = when (tx) {
    is BurnTransaction -> -delta
    is SendTransaction -> if (tx.author == currentId) -delta else delta
    else -> delta
}

private fun DisplayTransaction.sortableType(currentId: String?): Int = when (tx) {
    is MintTransaction -> 1
    is BurnTransaction -> 2
    is SendTransaction -> if (tx.author == currentId) 3 else 4
    is GenesisTransaction -> 5
}
