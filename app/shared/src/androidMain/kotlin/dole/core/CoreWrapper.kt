package dole.core

import android.util.Log
import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoFactory
import java.io.File

internal actual fun createPlatformDitto(config: DittoConfig): Ditto = DittoFactory.create(config = config)

internal actual fun resetBenchmarkDirectory(path: String): Boolean {
    val directory = File(path).absoluteFile.normalize()
    return directory.name.endsWith("_benchmark_store") && (!directory.exists() || directory.deleteRecursively()) && directory.mkdirs()
}

internal actual fun deleteBenchmarkDirectory(path: String): Boolean {
    val directory = File(path).absoluteFile.normalize()
    return directory.name.endsWith("_benchmark_store") && (!directory.exists() || directory.deleteRecursively())
}

internal actual fun logBenchmark(message: String) {
    Log.i("dole.bench", message)
}

internal actual object NativeCore {
    actual fun bytesToHex(bytes: ByteArray): String = dole.core.bytesToHex(bytes)
    actual fun hexToBytes(s: String): ByteArray = dole.core.hexToBytes(s)
    actual fun getPersonIdAsHex(pubKey: ByteArray): String = dole.core.getPersonIdAsHex(pubKey)
    actual fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean = dole.core.verifyCardCertificate(pubKey, cert)

    actual fun prepareGenesisTransaction(
        publicKeyId: String,
        publicKey: String,
        sigHex: String,
        certHex: String,
        timestamp: Long?
    ): NativeLedgerTransaction? = dole.core.prepareGenesisTransaction(
        publicKeyId,
        publicKey,
        sigHex,
        certHex,
        timestamp
    )?.toNative()

    actual fun prepareLedgerTransaction(
        publicKey: String,
        txType: String,
        targetId: String,
        goc: Long,
        seq: Long,
        sigHex: String,
        timestamp: Long?
    ): NativeLedgerTransaction? = dole.core.prepareLedgerTransaction(
        publicKey,
        txType,
        targetId,
        goc,
        seq,
        sigHex,
        timestamp
    )?.toNative()

    actual fun benchGenerateTransactions(): List<NativeLedgerTransaction> = dole.core.benchGenerateTransactions().map { it.toNative() }
}

private fun LedgerTransaction.toNative() = NativeLedgerTransaction(
    id = id,
    txType = txType,
    author = author,
    publicKey = publicKey,
    certificate = certificate,
    targetId = targetId,
    goc = goc,
    seq = seq,
    timestamp = timestamp,
    signature = signature
)
