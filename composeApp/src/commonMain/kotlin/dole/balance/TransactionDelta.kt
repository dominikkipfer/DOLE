package dole.balance

import dole.transaction.Transaction

data class TransactionDelta(
    val tx: Transaction,
    val delta: Long
)