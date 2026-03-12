package dole.transaction

data class GenesisTransaction(
    override val id: String,
    override val seq: Int,
    override val author: String,
    val attachmentCertificate: ByteArray?
) : Transaction