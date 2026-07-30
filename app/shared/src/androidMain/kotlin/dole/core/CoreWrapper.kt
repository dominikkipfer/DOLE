package dole.core

import dole.viewmodel.PeerConnection
import dole.viewmodel.TransportStatus

actual object CoreWrapper {
    actual val isBleSupported: Boolean = true

    private var ledger: Ledger? = null

    actual fun startGlobalSync(storagePath: String) = dole.core.startGlobalSync(storagePath)

    actual fun stopGlobalSync() = dole.core.stopGlobalSync()

    actual fun initLedger(onStateUpdated: (Long, String) -> Unit, storagePath: String, publicKeyId: String, publicKeyFull: String) {
        val rustListener = object : LedgerStateListener {
            override fun onStateUpdated(balance: Long, transactionHistoryJson: String) {
                onStateUpdated(balance, transactionHistoryJson)
            }
        }
        ledger = Ledger.initLedger(rustListener, storagePath, publicKeyId, publicKeyFull)
    }

    actual fun shutdown() {
        ledger?.shutdown()
        ledger = null
    }

    actual fun resetLedger(storagePath: String): Boolean = dole.core.resetLedger(storagePath)

    actual fun genesis(sigHex: String, certHex: String) {
        ledger?.genesis(sigHex, certHex)
    }

    actual fun mint(goc: Long, seq: Long, sigHex: String): Boolean = ledger?.mint(goc, seq, sigHex) ?: false

    actual fun burn(goc: Long, seq: Long, sigHex: String): Boolean = ledger?.burn(goc, seq, sigHex) ?: false

    actual fun send(targetPubKey: String, goc: Long, seq: Long, sigHex: String): Boolean = ledger?.send(targetPubKey, goc, seq, sigHex) ?: false

    actual fun bytesToHex(bytes: ByteArray): String = dole.core.bytesToHex(bytes)

    actual fun hexToBytes(s: String): ByteArray = dole.core.hexToBytes(s)

    actual fun getPersonIdAsHex(pubKey: ByteArray): String = dole.core.getPersonIdAsHex(pubKey)

    actual fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean = dole.core.verifyCardCertificate(pubKey, cert)

    actual fun startBleAdvertising(storagePath: String): Boolean = dole.core.startBleAdvertising(storagePath)

    actual fun stopBleAdvertising() = dole.core.stopBleAdvertising()

    actual fun setInternetEnabled(enabled: Boolean) = dole.core.setInternetEnabled(enabled)

    actual fun setIrohEnabled(enabled: Boolean) = dole.core.setIrohEnabled(enabled)

    actual fun localSessionId(): String = dole.core.localSessionId()

    actual fun connectedPeers(): List<PeerConnection> = dole.core.connectedPeers().map {
        PeerConnection(it.sessionId, it.ble, it.mdns, it.internet)
    }

    actual fun transportStatus(): TransportStatus = dole.core.transportStatus().let {
        TransportStatus(it.ble, it.iroh, it.internet)
    }

    actual fun benchSetMode(enabled: Boolean) = dole.core.benchSetMode(enabled)

    actual fun benchModeEnabled(): Boolean = dole.core.benchModeEnabled()

    actual fun benchGenerateWorkload(storagePath: String): Int = dole.core.benchGenerateWorkload(storagePath).toInt()

    actual fun benchRunStore(storagePath: String): Int = dole.core.benchRunStore(storagePath).toInt()

    actual fun benchRunLatency(storagePath: String): Boolean = dole.core.benchRunLatency(storagePath)

    actual fun benchLatencyReport(): String? = dole.core.benchLatencyReport()
}
