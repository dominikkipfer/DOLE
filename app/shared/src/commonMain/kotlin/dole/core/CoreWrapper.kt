package dole.core

import dole.viewmodel.PeerConnection
import dole.viewmodel.TransportStatus

expect object CoreWrapper {
    val isBleSupported: Boolean

    fun startGlobalSync(storagePath: String)
    fun stopGlobalSync()
    fun initLedger(onStateUpdated: (Long, String) -> Unit, storagePath: String, publicKeyId: String, publicKeyFull: String)
    fun shutdown()
    fun resetLedger(storagePath: String): Boolean

    fun genesis(sigHex: String, certHex: String)
    fun mint(goc: Long, seq: Long, sigHex: String): Boolean
    fun burn(goc: Long, seq: Long, sigHex: String): Boolean
    fun send(targetPubKey: String, goc: Long, seq: Long, sigHex: String): Boolean

    fun bytesToHex(bytes: ByteArray): String
    fun hexToBytes(s: String): ByteArray
    fun getPersonIdAsHex(pubKey: ByteArray): String
    fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean
    fun startBleAdvertising(storagePath: String): Boolean
    fun stopBleAdvertising()
    fun setInternetEnabled(enabled: Boolean)
    fun setIrohEnabled(enabled: Boolean)
    fun localSessionId(): String
    fun connectedPeers(): List<PeerConnection>
    fun transportStatus(): TransportStatus

    fun benchSetMode(enabled: Boolean)
    fun benchModeEnabled(): Boolean
    fun benchGenerateWorkload(storagePath: String): Int
    fun benchRunStore(storagePath: String): Int
    fun benchRunLatency(storagePath: String): Boolean
    fun benchLatencyReport(): String?
}
