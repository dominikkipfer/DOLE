package dole.core

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoConfig
import dole.viewmodel.PeerConnection
import dole.viewmodel.TransportStatus

data class NativeLedgerTransaction(
    val id: String,
    val txType: String,
    val author: String,
    val publicKey: String,
    val certificate: String,
    val targetId: String,
    val goc: Long,
    val seq: Long,
    val timestamp: Long,
    val signature: String
)

internal expect object NativeCore {
    fun bytesToHex(bytes: ByteArray): String
    fun hexToBytes(s: String): ByteArray
    fun getPersonIdAsHex(pubKey: ByteArray): String
    fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean
    fun prepareGenesisTransaction(
        publicKeyId: String,
        publicKey: String,
        sigHex: String,
        certHex: String,
        timestamp: Long? = null
    ): NativeLedgerTransaction?
    fun prepareLedgerTransaction(
        publicKey: String,
        txType: String,
        targetId: String,
        goc: Long,
        seq: Long,
        sigHex: String,
        timestamp: Long? = null
    ): NativeLedgerTransaction?
    fun benchGenerateTransactions(): List<NativeLedgerTransaction>
}

internal expect fun createPlatformDitto(config: DittoConfig): Ditto
internal expect fun resetBenchmarkDirectory(path: String): Boolean
internal expect fun deleteBenchmarkDirectory(path: String): Boolean
internal expect fun logBenchmark(message: String)

object CoreWrapper {
    suspend fun startGlobalSync(storagePath: String) = DittoLedger.start(storagePath)
    fun stopGlobalSync() = DittoLedger.stop()

    fun initLedger(
        onStateUpdated: (Long, String) -> Unit,
        storagePath: String,
        publicKeyId: String,
        publicKeyFull: String
    ) = DittoLedger.initialize(onStateUpdated, storagePath, publicKeyId, publicKeyFull)

    fun shutdown() = DittoLedger.detachLedger()
    suspend fun resetLedger(storagePath: String): Boolean = DittoLedger.reset(storagePath)
    suspend fun genesis(sigHex: String, certHex: String): Boolean = DittoLedger.genesis(sigHex, certHex)
    suspend fun mint(goc: Long, seq: Long, sigHex: String): Boolean = DittoLedger.record("M", "", goc, seq, sigHex)
    suspend fun burn(goc: Long, seq: Long, sigHex: String): Boolean = DittoLedger.record("B", "", goc, seq, sigHex)
    suspend fun send(targetPubKey: String, goc: Long, seq: Long, sigHex: String): Boolean = DittoLedger.record("S", targetPubKey, goc, seq, sigHex)

    fun bytesToHex(bytes: ByteArray): String = NativeCore.bytesToHex(bytes)
    fun hexToBytes(s: String): ByteArray = NativeCore.hexToBytes(s)
    fun getPersonIdAsHex(pubKey: ByteArray): String = NativeCore.getPersonIdAsHex(pubKey)
    fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean = NativeCore.verifyCardCertificate(pubKey, cert)

    fun setBleEnabled(enabled: Boolean) = DittoLedger.setBleEnabled(enabled)
    fun setLocalEnabled(enabled: Boolean) = DittoLedger.setLocalEnabled(enabled)
    fun setInternetEnabled(enabled: Boolean) = DittoLedger.setInternetEnabled(enabled)
    fun refreshPermissions() = DittoLedger.refreshPermissions()
    fun localSessionId(): String = DittoLedger.localSessionId()
    fun connectedPeers(): List<PeerConnection> = DittoLedger.connectedPeers()
    fun transportStatus(): TransportStatus = DittoLedger.transportStatus()

    fun benchSetMode(enabled: Boolean) = DittoLedger.benchSetMode(enabled)
    fun benchModeEnabled(): Boolean = DittoLedger.benchModeEnabled()
    fun benchHasWorkload(): Boolean = DittoLedger.benchHasWorkload()
    fun benchGenerateWorkload(): Int = DittoLedger.benchGenerateWorkload()
    suspend fun benchRunScale(storagePath: String): Boolean = DittoLedger.benchRunScale(storagePath)
    suspend fun benchRunStore(storagePath: String): Int = DittoLedger.benchRunStore(storagePath)
    fun benchStorageReport(): String? = DittoLedger.benchStorageReport()
    suspend fun benchRunLatency(storagePath: String): Boolean = DittoLedger.benchRunLatency(storagePath)
    fun benchLatencyReport(): String? = DittoLedger.benchLatencyReport()
}
