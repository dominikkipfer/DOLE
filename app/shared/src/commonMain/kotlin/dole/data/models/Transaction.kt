package dole.data.models

sealed interface Transaction {
    val id: String
    val author: String
    val timestamp: Long
    val seq: Long
    val signature: String
}

data class BurnTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    override val seq: Long,
    override val signature: String = "",
    val goc: Long
) : Transaction

data class GenesisTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    override val seq: Long,
    override val signature: String = "",
    val publicKey: String,
    val attachmentCertificate: ByteArray?
) : Transaction {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as GenesisTransaction

        if (id != other.id) return false
        if (author != other.author) return false
        if (timestamp != other.timestamp) return false
        if (seq != other.seq) return false
        if (publicKey != other.publicKey) return false
        if (attachmentCertificate != null) {
            if (other.attachmentCertificate == null) return false
            if (!attachmentCertificate.contentEquals(other.attachmentCertificate)) return false
        } else if (other.attachmentCertificate != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + seq.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + (attachmentCertificate?.contentHashCode() ?: 0)
        return result
    }
}

data class MintTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    override val seq: Long,
    override val signature: String = "",
    val goc: Long
) : Transaction

data class SendTransaction(
    override val id: String,
    override val author: String,
    override val timestamp: Long,
    override val seq: Long,
    override val signature: String = "",
    val target: String,
    val goc: Long
) : Transaction