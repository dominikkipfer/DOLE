package dole.card

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import dole.Constants
import java.io.IOException
import java.nio.ByteBuffer

class AndroidSmartCard(tag: Tag?) : SmartCard {
    private val isoDep: IsoDep = IsoDep.get(tag) ?: throw IOException(Constants.ERR_CARD_NOT_FOUND)
    private var isConnectedInternal = false

    override val isConnected: Boolean get() = isConnectedInternal && isoDep.isConnected

    override fun connect() {
        if (!isoDep.isConnected) {
            try {
                isoDep.connect()
                isoDep.timeout = 5000
            } catch (_: IOException) {
                throw IOException(Constants.ERR_CARD_NOT_FOUND)
            }
        }
        try {
            selectApplet()
            isConnectedInternal = true
        } catch (_: Exception) {
            disconnect()
            throw IOException(Constants.ERR_CARD_NOT_FOUND)
        }
    }

    override fun disconnect() {
        try {
            if (isoDep.isConnected) isoDep.close()
        } catch (e: IOException) {
            Log.w("AndroidSmartCard", "Error closing IsoDep", e)
        }
        isConnectedInternal = false
    }

    private fun selectApplet() {
        val command = buildApdu(0x00, 0xA4, 0x04, Constants.APPLET_AID_BYTES)
        checkStatusWord(isoDep.transceive(command))
    }

    private fun transmit(ins: Int, data: ByteArray?): ByteArray {
        if (!isConnected) throw IOException(Constants.ERR_CARD_NOT_FOUND)
        val response = isoDep.transceive(buildApdu(Constants.CLA_PROPRIETARY, ins, 0x00, data))
        checkStatusWord(response)
        return if (response.size > 2) response.copyOf(response.size - 2) else ByteArray(0)
    }

    private fun buildApdu(cla: Int, ins: Int, p1: Int, data: ByteArray?): ByteArray {
        val dataLen = data?.size ?: 0
        val buf = ByteBuffer.allocate(5 + dataLen)
        buf.put(cla.toByte()).put(ins.toByte()).put(p1.toByte()).put(0x00.toByte()).put(dataLen.toByte())
        data?.let { buf.put(it) }
        return buf.array()
    }

    private fun checkStatusWord(response: ByteArray) {
        if (response.size < 2) throw Exception("Invalid response length")
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        val sw = (sw1 shl 8) or sw2
        if (sw != 0x9000) throw Exception("Card Error SW: ${Integer.toHexString(sw)}")
    }

    override fun verifyPin(pin: ByteArray): Boolean {
        return try {
            transmit(Constants.OP_VERIFY_PIN.toInt(), pin)
            true
        } catch (_: Exception) { false }
    }

    override fun changePin(newPin: ByteArray): Boolean {
        return try {
            transmit(Constants.OP_CHANGE_PIN.toInt(), newPin)
            true
        } catch (_: Exception) {
            false
        }
    }

    override val publicKey: ByteArray get() = transmit(Constants.OP_GET_PUBKEY.toInt(), null)
    override val certificate: ByteArray get() = transmit(Constants.OP_GET_CERT.toInt(), null)

    override fun processGenesis() = transmit(Constants.OP_GENESIS.toInt(), ByteArray(0))
    override fun processMint(payload: ByteArray) = transmit(Constants.OP_MINT.toInt(), payload)
    override fun processBurn(payload: ByteArray) = transmit(Constants.OP_BURN.toInt(), payload)
    override fun processSend(payload: ByteArray) = transmit(Constants.OP_SEND.toInt(), payload)
    override fun processReceive(payload: ByteArray) { transmit(Constants.OP_RECEIVE.toInt(), payload) }
    override fun addPeer(payload: ByteArray) { transmit(Constants.OP_ADD_PEER.toInt(), payload) }

    override val isMinter: Boolean get() = transmit(Constants.OP_GET_STATUS.toInt(), null).let {
        it.isNotEmpty() && it[0] == 0x01.toByte()
    }

    override val isPinSet: Boolean get() = transmit(Constants.OP_GET_STATUS.toInt(), null).let {
        it.size > 1 && it[1] == 0x01.toByte()
    }

    override val isGenesisDone: Boolean get() = transmit(Constants.OP_GET_STATUS.toInt(), null).let {
        it.size > 2 && it[2] == 0x01.toByte()
    }

    override val pinRetries: Int get() = transmit(Constants.OP_GET_STATUS.toInt(), null).let {
        if (it.size > 3) it[3].toInt() else 3
    }
}