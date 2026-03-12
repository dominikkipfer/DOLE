package dole.balance

import dole.transaction.GenesisTransaction
import dole.transaction.SendTransaction
import dole.transaction.Transaction
import dole.transaction.MintTransaction
import dole.transaction.BurnTransaction

object BalanceCalculator {
    fun calculate(
        transactions: List<Transaction>?,
        currentId: String?
    ): BalanceResult {
        if (currentId == null || transactions.isNullOrEmpty()) return BalanceResult(0, emptyList())

        val sorted = transactions.sortedBy { it.seq }
        val displayList = mutableListOf<TransactionDelta>()

        var myMintGoc = 0L
        var myBurnGoc = 0L

        val mySendGocs = mutableMapOf<String, Long>()
        val receivedGocs = mutableMapOf<String, Long>()

        var totalBalance = 0L

        for (tx in sorted) {
            val isAuthor = currentId == tx.author
            val isTarget = (tx is SendTransaction) && currentId == tx.target

            if (!isAuthor && !isTarget) continue

            var delta = 0L

            when (tx) {
                is MintTransaction -> {
                    if (tx.goc > myMintGoc) {
                        delta = tx.goc - myMintGoc
                        myMintGoc = tx.goc
                        totalBalance += delta
                    }
                }
                is BurnTransaction -> {
                    if (tx.goc > myBurnGoc) {
                        delta = tx.goc - myBurnGoc
                        myBurnGoc = tx.goc
                        totalBalance -= delta
                    }
                }
                is SendTransaction -> {
                    if (isAuthor) {
                        val target = tx.target
                        val lastGoc = mySendGocs[target] ?: 0L

                        if (tx.goc > lastGoc) {
                            delta = tx.goc - lastGoc
                            mySendGocs[target] = tx.goc
                            totalBalance -= delta
                        }
                    } else {
                        val sender = tx.author
                        val lastGoc = receivedGocs[sender] ?: 0L

                        if (tx.goc > lastGoc) {
                            delta = tx.goc - lastGoc
                            receivedGocs[sender] = tx.goc
                            totalBalance += delta
                        }
                    }
                }
                else -> {}
            }
            if (delta > 0 || (tx is GenesisTransaction && isAuthor)) displayList.add(TransactionDelta(tx, delta))
        }

        displayList.reverse()
        return BalanceResult(totalBalance.toInt(), displayList)
    }
}