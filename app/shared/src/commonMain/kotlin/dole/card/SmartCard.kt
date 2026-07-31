package dole.card

import dole.Constants

data class CardSecureState(val balance: Long, val seq: Long)
data class CardPeerState(val received: Long, val sent: Long)

private fun ByteArray.beLongAt(offset: Int): Long {
    var value = 0L
    for (i in 0 until Constants.LONG_SIZE.toInt()) value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
    return value
}

fun readSecureState(payload: ByteArray): CardSecureState? {
    if (payload.size < Constants.CARD_SECURE_STATUS_SIZE.toInt()) return null
    return CardSecureState(
        balance = payload.beLongAt(Constants.CARD_SECURE_OFFSET_BALANCE.toInt()),
        seq = payload.beLongAt(Constants.CARD_SECURE_OFFSET_SEQ.toInt())
    )
}

fun readPeerState(payload: ByteArray): CardPeerState? {
    if (payload.size < Constants.CARD_PEER_STATE_SIZE.toInt()) return null
    return CardPeerState(
        received = payload.beLongAt(Constants.CARD_PEER_STATE_OFFSET_RECEIVED.toInt()),
        sent = payload.beLongAt(Constants.CARD_PEER_STATE_OFFSET_SENT.toInt())
    )
}

fun readPinEpoch(status: ByteArray): Int {
    val offset = Constants.CARD_STATUS_OFFSET_PIN_EPOCH.toInt()
    if (status.size < offset + 4) return -1

    var value = 0
    for (i in 0 until 4) value = (value shl 8) or (status[offset + i].toInt() and 0xFF)
    return value
}

interface SmartCard {
    @Throws(Exception::class)
    fun connect()
    fun disconnect()

    fun probe(): Boolean = try {
        if (!isConnected) connect()
        isConnected
    } catch (_: Exception) {
        false
    }

    fun completeSession() {}

    val isConnected: Boolean
    val isMinter: Boolean
    val isPinSet: Boolean
    val isGenesisDone: Boolean
    val pinRetries: Int
    val pinEpoch: Int
    val publicKey: ByteArray?
    val certificate: ByteArray?

    fun secureState(): CardSecureState?
    fun peerState(publicKey: ByteArray): CardPeerState?
    fun verifyPin(pin: ByteArray): Boolean
    fun changePin(newPin: ByteArray): Boolean
    @Throws(Exception::class)
    fun processGenesis(): ByteArray
    @Throws(Exception::class)
    fun processMint(payload: ByteArray): ByteArray
    @Throws(Exception::class)
    fun processBurn(payload: ByteArray): ByteArray
    @Throws(Exception::class)
    fun processSend(payload: ByteArray): ByteArray
    @Throws(Exception::class)
    fun processReceive(payload: ByteArray)
    @Throws(Exception::class)
    fun addPeer(payload: ByteArray)
}
