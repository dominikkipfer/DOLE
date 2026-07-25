package dole.utils

import dole.Constants

object ProtocolSerializer {

    fun buildMintBurnPayload(amount: Long): ByteArray {
        require(amount > 0) { "Amount must be > 0" }
        return ByteWriter(Constants.LONG_SIZE.toInt())
            .putLong(amount)
            .array
    }

    fun buildSendPayload(targetPubKey: ByteArray, amount: Long): ByteArray {
        require(amount > 0) { "Amount must be > 0" }
        require(targetPubKey.size == Constants.PUBKEY_SIZE.toInt()) { "Public key must be ${Constants.PUBKEY_SIZE} bytes" }
        return ByteWriter(Constants.APDU_SEND_SIZE.toInt())
            .putBytes(targetPubKey)
            .putLong(amount)
            .array
    }

    fun buildReceivePayload(senderPublicKey: ByteArray, signature: ByteArray, logPayload: ByteArray): ByteArray {
        require(senderPublicKey.size == Constants.PUBKEY_SIZE.toInt()) { "Sender public key must be ${Constants.PUBKEY_SIZE} bytes" }
        require(signature.isNotEmpty()) { "Signature cannot be empty" }
        require(logPayload.size == Constants.LOG_SEND_SIZE.toInt()) { "Log payload must be ${Constants.LOG_SEND_SIZE} bytes" }

        val totalSize = lengthPrefixed(senderPublicKey) + lengthPrefixed(signature) + logPayload.size
        return ByteWriter(totalSize)
            .putLengthPrefixed(senderPublicKey)
            .putLengthPrefixed(signature)
            .putBytes(logPayload)
            .array
    }

    fun buildAddPeerPayload(certificate: ByteArray, publicKey: ByteArray): ByteArray {
        require(publicKey.size == Constants.PUBKEY_SIZE.toInt()) { "Public key must be ${Constants.PUBKEY_SIZE} bytes" }

        val totalSize = lengthPrefixed(certificate) + lengthPrefixed(publicKey)
        return ByteWriter(totalSize)
            .putLengthPrefixed(certificate)
            .putLengthPrefixed(publicKey)
            .array
    }

    fun buildLogPayload(seq: Long, type: Byte, targetId: ByteArray?, goc: Long): ByteArray {
        val payloadSize = when (type) {
            Constants.OP_GENESIS -> Constants.LOG_GENESIS_SIZE.toInt()
            Constants.OP_MINT, Constants.OP_BURN -> Constants.LOG_MINTBURN_SIZE.toInt()
            Constants.OP_SEND -> Constants.LOG_SEND_SIZE.toInt()
            else -> throw IllegalArgumentException("Unsupported type: $type")
        }

        val buffer = ByteArray(payloadSize)
        buffer[Constants.LOG_OFFSET_TYPE.toInt()] = type
        buffer.putLong(Constants.LOG_OFFSET_SEQ.toInt(), seq)

        when (type) {
            Constants.OP_MINT, Constants.OP_BURN -> {
                buffer.putLong(Constants.LOG_MINTBURN_OFFSET_GOC.toInt(), goc)
            }
            Constants.OP_SEND -> {
                requireNotNull(targetId) { "Target ID required for SEND" }
                require(targetId.size == Constants.ID_SIZE.toInt()) { "Target ID must be ${Constants.ID_SIZE} bytes" }
                targetId.copyInto(buffer, Constants.LOG_SEND_OFFSET_TARGET.toInt())
                buffer.putLong(Constants.LOG_SEND_OFFSET_GOC.toInt(), goc)
            }
        }
        return buffer
    }

    fun parseSeqFromResponse(response: ByteArray): Long {
        require(response.size >= Constants.LONG_SIZE.toInt()) { "Response too short" }
        return response.getLong(0)
    }

    fun parseGocFromResponse(response: ByteArray): Long {
        require(response.size >= signatureOffset()) { "Response too short" }
        return response.getLong(Constants.LONG_SIZE.toInt())
    }

    fun parseSignatureFromResponse(response: ByteArray): ByteArray {
        require(response.size > signatureOffset()) { "Response too short" }
        return response.copyOfRange(signatureOffset(), response.size)
    }

    private fun signatureOffset() = Constants.LONG_SIZE.toInt() * 2

    fun parseGenesisSignatureFromResponse(response: ByteArray): ByteArray {
        require(response.isNotEmpty()) { "Response too short" }
        return response
    }

    fun validateAndConvertPin(pin: CharArray): ByteArray {
        require(pin.size == Constants.PIN_SIZE.toInt()) { "PIN must be exactly ${Constants.PIN_SIZE} digits" }
        return ByteArray(pin.size) { i -> pin[i].code.toByte() }
    }
}

private fun lengthPrefixed(field: ByteArray): Int = Short.SIZE_BYTES + field.size

private class ByteWriter(size: Int) {
    val array = ByteArray(size)
    private var pos = 0

    fun putLong(value: Long) = apply {
        array.putLong(pos, value)
        pos += Long.SIZE_BYTES
    }

    fun putBytes(src: ByteArray) = apply {
        src.copyInto(array, pos)
        pos += src.size
    }

    fun putLengthPrefixed(src: ByteArray) = apply {
        array.putShort(pos, src.size.toShort())
        pos += Short.SIZE_BYTES
        putBytes(src)
    }
}

private fun ByteArray.putLong(offset: Int, value: Long) {
    this[offset]     = (value ushr 56).toByte()
    this[offset + 1] = (value ushr 48).toByte()
    this[offset + 2] = (value ushr 40).toByte()
    this[offset + 3] = (value ushr 32).toByte()
    this[offset + 4] = (value ushr 24).toByte()
    this[offset + 5] = (value ushr 16).toByte()
    this[offset + 6] = (value ushr 8).toByte()
    this[offset + 7] = value.toByte()
}

private fun ByteArray.putShort(offset: Int, value: Short) {
    this[offset]     = (value.toInt() ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.getLong(offset: Int): Long {
    return ((this[offset].toLong() and 0xFF) shl 56) or
            ((this[offset + 1].toLong() and 0xFF) shl 48) or
            ((this[offset + 2].toLong() and 0xFF) shl 40) or
            ((this[offset + 3].toLong() and 0xFF) shl 32) or
            ((this[offset + 4].toLong() and 0xFF) shl 24) or
            ((this[offset + 5].toLong() and 0xFF) shl 16) or
            ((this[offset + 6].toLong() and 0xFF) shl 8) or
            (this[offset + 7].toLong() and 0xFF)
}
