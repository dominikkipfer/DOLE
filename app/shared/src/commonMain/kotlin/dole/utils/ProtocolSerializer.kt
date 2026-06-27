package dole.utils

import dole.Constants

object ProtocolSerializer {

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

    fun buildMintBurnPayload(amount: Long): ByteArray {
        require(amount > 0) { "Amount must be > 0" }
        val buffer = ByteArray(Constants.LONG_SIZE.toInt())
        buffer.putLong(0, amount)
        return buffer
    }

    fun buildSendPayload(targetPubKey: ByteArray, amount: Long): ByteArray {
        require(amount > 0) {
            "Amount must be > 0"
        }
        require(targetPubKey.size == Constants.PUBKEY_SIZE.toInt()) { "Public key must be ${Constants.PUBKEY_SIZE} bytes" }
        val buffer = ByteArray(Constants.APDU_SEND_SIZE.toInt())
        targetPubKey.copyInto(buffer, 0)
        buffer.putLong(targetPubKey.size, amount)
        return buffer
    }

    fun buildReceivePayload(senderPublicKey: ByteArray, signature: ByteArray, logPayload: ByteArray): ByteArray {
        require(senderPublicKey.size == Constants.PUBKEY_SIZE.toInt()) { "Sender public key must be ${Constants.PUBKEY_SIZE} bytes" }
        require(signature.isNotEmpty()) { "Signature cannot be empty" }
        require(logPayload.size == Constants.LOG_SEND_SIZE.toInt()) { "Log payload must be ${Constants.LOG_SEND_SIZE} bytes" }

        val totalSize = 2 + senderPublicKey.size + 2 + signature.size + logPayload.size
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
        require(publicKey.size == Constants.PUBKEY_SIZE.toInt()) { "Public key must be ${Constants.PUBKEY_SIZE} bytes" }

        val totalSize = 2 + certificate.size + 2 + publicKey.size
        val buffer = ByteArray(totalSize)
        var offset = 0

        buffer.putShort(offset, certificate.size.toShort()); offset += 2
        certificate.copyInto(buffer, offset); offset += certificate.size
        buffer.putShort(offset, publicKey.size.toShort()); offset += 2
        publicKey.copyInto(buffer, offset)

        return buffer
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

    fun parseSignatureFromResponse(response: ByteArray): ByteArray {
        require(response.size > Constants.LONG_SIZE.toInt()) { "Response too short" }
        return response.copyOfRange(Constants.LONG_SIZE.toInt(), response.size)
    }

    fun parseGenesisSignatureFromResponse(response: ByteArray): ByteArray {
        require(response.isNotEmpty()) { "Response too short" }
        return response
    }

    fun validateAndConvertPin(pin: CharArray): ByteArray {
        require(pin.size == Constants.PIN_SIZE.toInt()) { "PIN must be exactly ${Constants.PIN_SIZE} digits" }
        val pinBytes = ByteArray(Constants.PIN_SIZE.toInt())
        for (i in pin.indices) pinBytes[i] = pin[i].code.toByte()
        return pinBytes
    }
}