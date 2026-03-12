package dole.card

interface SmartCard {
    fun connect()
    fun disconnect()
    val isConnected: Boolean
    val isMinter: Boolean
    val isPinSet: Boolean
    val isGenesisDone: Boolean
    val pinRetries: Int
    val publicKey: ByteArray?
    val certificate: ByteArray?

    fun verifyPin(pin: ByteArray): Boolean
    fun changePin(newPin: ByteArray): Boolean
    fun processGenesis(): ByteArray
    fun processMint(payload: ByteArray): ByteArray
    fun processBurn(payload: ByteArray): ByteArray
    fun processSend(payload: ByteArray): ByteArray
    fun processReceive(payload: ByteArray)
    fun addPeer(payload: ByteArray)
}