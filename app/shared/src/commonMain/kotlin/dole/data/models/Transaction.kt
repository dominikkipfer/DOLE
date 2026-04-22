package dole.data.models

sealed interface Transaction {
    val id: String
    val author: String
    val timestamp: Long
}

data class BurnTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    val goc: Long
) : Transaction

data class GenesisTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    val attachmentCertificate: ByteArray?
) : Transaction {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
		if (other == null || this::class != other::class) return false

        other as GenesisTransaction

        if (timestamp != other.timestamp) return false
        if (id != other.id) return false
        if (author != other.author) return false
        if (!attachmentCertificate.contentEquals(other.attachmentCertificate)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + (attachmentCertificate?.contentHashCode() ?: 0)
        return result
    }
}

data class MintTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    val goc: Long
) : Transaction

data class SendTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    val target: String,
    val goc: Long
) : Transaction
