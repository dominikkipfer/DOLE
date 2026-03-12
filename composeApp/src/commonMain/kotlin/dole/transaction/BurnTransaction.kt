package dole.transaction

data class BurnTransaction(
    override val id: String,
    override val seq: Int,
    override val author: String,
    val goc: Long
) : Transaction