package dole.transaction

data class MintTransaction(
    override val id: String,
    override val seq: Int,
    override val author: String,
    val goc: Long
) : Transaction