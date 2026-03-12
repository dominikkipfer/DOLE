package dole.crypto

import dole.Constants

object ProtocolSerializer {

    private fun ByteArray.putInt(offset: Int, value: Int) {
        this[offset] = (value shr 24).toByte()
        this[offset + 1] = (value shr 16).toByte()
        this[offset + 2] = (value shr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun ByteArray.putShort(offset: Int, value: Short) {
        this[offset] = (value.toInt() shr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.getInt(offset: Int): Int {
        return ((this[offset].toInt() and 0xFF) shl 24) or
                ((this[offset + 1].toInt() and 0xFF) shl 16) or
                ((this[offset + 2].toInt() and 0xFF) shl 8) or
                (this[offset + 3].toInt() and 0xFF)
    }

    fun buildMintBurnPayload(pin: ByteArray, amount: Int): ByteArray {
        validatePin(pin)
        require(amount > 0) { "Amount must be over zero" }
        val buffer = ByteArray(Constants.APDU_MINT_BURN_SIZE.toInt())
        buffer.putInt(0, amount)
        return buffer
    }

    fun buildSendPayload(pin: ByteArray, targetIdHex: String, amount: Int): ByteArray {
        validatePin(pin)
        require(amount > 0) { "Amount must be over zero" }
        val targetId = validateAndConvertId(targetIdHex)

        val buffer = ByteArray(Constants.APDU_SEND_SIZE.toInt())
        targetId.copyInto(buffer, 0)
        buffer.putInt(targetId.size, amount)
        return buffer
    }

    fun buildReceivePayload(pin: ByteArray, senderPublicKey: ByteArray, signature: ByteArray, logPayload: ByteArray): ByteArray {
        validatePin(pin)
        require(senderPublicKey.isNotEmpty()) { "Sender public key cannot be empty" }
        require(signature.isNotEmpty()) { "Signature cannot be empty" }
        require(logPayload.size == Constants.LOG_PAYLOAD_SIZE.toInt()) { "Invalid log payload size" }

        val totalSize = Constants.LEN_SIZE + senderPublicKey.size + Constants.LEN_SIZE + signature.size + Constants.LOG_PAYLOAD_SIZE
        val buffer = ByteArray(totalSize)

        var offset = 0
        buffer.putShort(offset, senderPublicKey.size.toShort()); offset += 2
        senderPublicKey.copyInto(buffer, offset); offset += senderPublicKey.size
        buffer.putShort(offset, signature.size.toShort()); offset += 2
        signature.copyInto(buffer, offset); offset += signature.size
        logPayload.copyInto(buffer, offset)

        return buffer
    }

    fun buildAddPeerPayload(certificate: ByteArray, publicKey: ByteArray): ByteArray {
        val totalSize = Constants.LEN_SIZE + certificate.size + Constants.LEN_SIZE + publicKey.size
        val buffer = ByteArray(totalSize)
        var offset = 0

        buffer.putShort(offset, certificate.size.toShort()); offset += 2
        certificate.copyInto(buffer, offset); offset += certificate.size
        buffer.putShort(offset, publicKey.size.toShort()); offset += 2
        publicKey.copyInto(buffer, offset)

        return buffer
    }

    fun buildLogPayload(seq: Int, prevHash: ByteArray, type: Byte, author: ByteArray, target: ByteArray?, goc: Int): ByteArray {
        val actualTarget = if (type == Constants.OP_GENESIS || type == Constants.OP_MINT || type == Constants.OP_BURN) null else target
        if (type == Constants.OP_SEND) {
            requireNotNull(actualTarget) { "Target ID is required for SEND operation" }
        }
        val targetBytes = actualTarget ?: ByteArray(Constants.ID_SIZE.toInt())

        val buffer = ByteArray(Constants.LOG_PAYLOAD_SIZE.toInt())
        var offset = 0
        buffer.putInt(offset, seq); offset += 4
        prevHash.copyInto(buffer, offset); offset += prevHash.size
        buffer[offset] = type; offset += 1
        author.copyInto(buffer, offset); offset += author.size
        targetBytes.copyInto(buffer, offset); offset += targetBytes.size
        buffer.putInt(offset, goc)

        return buffer
    }

    fun buildLogPayloadFromHex(seq: Int, prevHashHex: String, type: Byte, authorHex: String, targetHex: String?, goc: Int): ByteArray {
        val prevHash = validateAndConvertHash(prevHashHex)
        val author = validateAndConvertId(authorHex)
        val target = if (targetHex != null && !isZeroHex(targetHex)) validateAndConvertId(targetHex) else null
        return buildLogPayload(seq, prevHash, type, author, target, goc)
    }

    fun extractSeq(logPayload: ByteArray): Int {
        require(logPayload.size == Constants.LOG_PAYLOAD_SIZE.toInt())
        return logPayload.getInt(Constants.LOG_OFFSET_SEQ.toInt())
    }

    fun extractType(logPayload: ByteArray): Byte {
        return logPayload[Constants.LOG_OFFSET_TYPE.toInt()]
    }

    fun extractGoc(logPayload: ByteArray): Int {
        return logPayload.getInt(Constants.LOG_OFFSET_GOC.toInt())
    }

    fun extractPrevHash(logPayload: ByteArray): ByteArray {
        return logPayload.copyOfRange(Constants.LOG_OFFSET_PREV_HASH.toInt(), Constants.LOG_OFFSET_PREV_HASH.toInt() + Constants.HASH_SIZE)
    }

    fun extractAuthor(logPayload: ByteArray): ByteArray {
        return logPayload.copyOfRange(Constants.LOG_OFFSET_AUTHOR.toInt(), Constants.LOG_OFFSET_AUTHOR.toInt() + Constants.ID_SIZE)
    }

    fun extractTarget(logPayload: ByteArray): ByteArray {
        return logPayload.copyOfRange(Constants.LOG_OFFSET_TARGET.toInt(), Constants.LOG_OFFSET_TARGET.toInt() + Constants.ID_SIZE)
    }

    private fun validateAndConvertHash(hashHex: String): ByteArray {
        require(hashHex.length == Constants.HASH_SIZE * Constants.HEX_CHARS_PER_BYTE) { "Invalid Hash hex length" }
        return CryptoUtils.hexToBytes(hashHex)
    }

    private fun validateAndConvertId(idHex: String): ByteArray {
        require(idHex.length == Constants.ID_SIZE * Constants.HEX_CHARS_PER_BYTE) { "Invalid ID hex length" }
        return CryptoUtils.hexToBytes(idHex)
    }

    private fun isZeroHex(hex: String?): Boolean {
        return hex == null || hex.matches(Regex("0+"))
    }

    fun validateAndConvertPin(pin: CharArray): ByteArray {
        require(pin.size == Constants.PIN_SIZE.toInt()) { "PIN must be exactly ${Constants.PIN_SIZE} digits" }
        val pinBytes = ByteArray(Constants.PIN_SIZE.toInt())
        for (i in pin.indices) pinBytes[i] = pin[i].code.toByte()
        validatePin(pinBytes)
        return pinBytes
    }

    private fun validatePin(pin: ByteArray) {
        require(pin.size == Constants.PIN_SIZE.toInt()) { "PIN must be exactly ${Constants.PIN_SIZE} bytes" }
    }
}