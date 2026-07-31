package dole.core

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoFactory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog

interface IosCore {
    fun bytesToHex(bytes: ByteArray): String
    fun hexToBytes(s: String): ByteArray
    fun getPersonIdAsHex(pubKey: ByteArray): String
    fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean
    fun prepareGenesisTransaction(
        publicKeyId: String,
        publicKey: String,
        sigHex: String,
        certHex: String,
        timestamp: Long?
    ): NativeLedgerTransaction?
    fun prepareLedgerTransaction(
        publicKey: String,
        txType: String,
        targetId: String,
        goc: Long,
        seq: Long,
        sigHex: String,
        timestamp: Long?
    ): NativeLedgerTransaction?
    fun benchGenerateTransactions(): List<NativeLedgerTransaction>
}

private var iosCore: IosCore? = null

fun installIosCore(core: IosCore) {
    iosCore = core
}

internal actual fun createPlatformDitto(config: DittoConfig): Ditto = DittoFactory.create(config = config)

internal actual fun resetBenchmarkDirectory(path: String): Boolean {
    val manager = NSFileManager.defaultManager
    return path.endsWith("_benchmark_store") &&
        (!manager.fileExistsAtPath(path) || manager.removeItemAtPath(path, null)) &&
        manager.createDirectoryAtPath(path, true, null, null)
}

internal actual fun deleteBenchmarkDirectory(path: String): Boolean {
    val manager = NSFileManager.defaultManager
    return path.endsWith("_benchmark_store") && (!manager.fileExistsAtPath(path) || manager.removeItemAtPath(path, null))
}

internal actual fun logBenchmark(message: String) {
    NSLog("%@", message)
}

internal actual object NativeCore {
    actual fun bytesToHex(bytes: ByteArray): String = iosCore?.bytesToHex(bytes).orEmpty()
    actual fun hexToBytes(s: String): ByteArray = iosCore?.hexToBytes(s) ?: ByteArray(0)
    actual fun getPersonIdAsHex(pubKey: ByteArray): String = iosCore?.getPersonIdAsHex(pubKey).orEmpty()
    actual fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean = iosCore?.verifyCardCertificate(pubKey, cert) ?: false

    actual fun prepareGenesisTransaction(
        publicKeyId: String,
        publicKey: String,
        sigHex: String,
        certHex: String,
        timestamp: Long?
    ): NativeLedgerTransaction? = iosCore?.prepareGenesisTransaction(publicKeyId, publicKey, sigHex, certHex, timestamp)

    actual fun prepareLedgerTransaction(
        publicKey: String,
        txType: String,
        targetId: String,
        goc: Long,
        seq: Long,
        sigHex: String,
        timestamp: Long?
    ): NativeLedgerTransaction? = iosCore?.prepareLedgerTransaction(
        publicKey,
        txType,
        targetId,
        goc,
        seq,
        sigHex,
        timestamp
    )

    actual fun benchGenerateTransactions(): List<NativeLedgerTransaction> = iosCore?.benchGenerateTransactions() ?: emptyList()
}
