package dole.transaction

data class SendTransaction(
    override val id: String,
    override val seq: Int,
    override val author: String,
    val target: String,
    val goc: Long
) : Transaction