package dole.ledger

import dole.Constants
import dole.crypto.CryptoUtils
import dole.crypto.ProtocolSerializer
import java.security.PublicKey

class LedgerEntry private constructor(
    private val rawPayload: ByteArray,
    val signature: ByteArray?,
    val seq: Int,
    val goc: Int,
    val type: Byte
) {
    var attachmentPublicKey: ByteArray? = null; private set
    var attachmentCertificate: ByteArray? = null; private set
    private var cachedHash: ByteArray? = null

    val payloadBytes: ByteArray
        get() = rawPayload.copyOf()

    val authorID: String
        get() = CryptoUtils.bytesToHex(ProtocolSerializer.extractAuthor(rawPayload))

    val targetID: String
        get() = CryptoUtils.bytesToHex(ProtocolSerializer.extractTarget(rawPayload))

    val prevHash: ByteArray
        get() = ProtocolSerializer.extractPrevHash(rawPayload)

    val hash: ByteArray
        get() {
            if (cachedHash == null) cachedHash = CryptoUtils.sha256(rawPayload)
            return cachedHash!!
        }

    fun verifyLogSig(pk: PublicKey?): Boolean {
        return CryptoUtils.verifySignature(pk, rawPayload, signature)
    }

    fun verifyCertificate(rootCA: PublicKey?): Boolean {
        val cert = attachmentCertificate
        val pubKey = attachmentPublicKey

        if (cert == null || pubKey == null) throw RuntimeException("Missing Attachment for Verification")

        val calculatedAuthorId = CryptoUtils.calculatePersonId(pubKey)
        val claimedAuthorId = ProtocolSerializer.extractAuthor(rawPayload)

        if (!calculatedAuthorId.contentEquals(claimedAuthorId)) {
            println("ID mismatch!")
            return false
        }
        return CryptoUtils.verifySignature(rootCA, pubKey, cert)
    }

    fun setAttachments(cert: ByteArray, pubKey: ByteArray) {
        this.attachmentPublicKey = pubKey
        this.attachmentCertificate = cert
    }

    companion object {
        fun fromCardResponse(cardOutput: ByteArray): LedgerEntry {
            require(cardOutput.size > Constants.LOG_PAYLOAD_SIZE) { "Card output too short" }

            val payload = cardOutput.copyOfRange(0, Constants.LOG_PAYLOAD_SIZE.toInt())
            val sig = cardOutput.copyOfRange(Constants.LOG_PAYLOAD_SIZE.toInt(), cardOutput.size)

            val seq = ProtocolSerializer.extractSeq(payload)
            val type = ProtocolSerializer.extractType(payload)
            val goc = ProtocolSerializer.extractGoc(payload)

            return LedgerEntry(
                payload,
                sig,
                seq,
                goc,
                type
            )
        }

        fun createFromNetwork(
            payload: ByteArray,
            sig: ByteArray?,
            seq: Int,
            goc: Int,
            type: Byte,
            pubKey: ByteArray? = null,
            cert: ByteArray? = null
        ): LedgerEntry {
            val entry =
                LedgerEntry(
                    payload,
                    sig,
                    seq,
                    goc,
                    type
                )
            if (pubKey != null && cert != null) entry.setAttachments(cert, pubKey)
            return entry
        }
    }
}