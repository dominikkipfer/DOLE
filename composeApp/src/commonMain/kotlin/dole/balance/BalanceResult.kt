package dole.balance

data class BalanceResult(
    val totalBalance: Int,
    val items: List<TransactionDelta>
)