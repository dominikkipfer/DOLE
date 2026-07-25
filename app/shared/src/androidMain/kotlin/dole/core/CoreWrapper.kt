package dole.core

import dole.viewmodel.PeerConnection
import dole.viewmodel.TransportStatus

actual object CoreWrapper {
    private var ledger: Ledger? = null

    actual fun startGlobalSync(storagePath: String) {
        dole.core.startGlobalSync(storagePath)
    }

    actual fun stopGlobalSync() {
        dole.core.stopGlobalSync()
    }

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

    actual fun resetLedger(storagePath: String): Boolean {
        return dole.core.resetLedger(storagePath)
    }

    actual fun genesis(sigHex: String, certHex: String) {
        ledger?.genesis(sigHex, certHex)
    }

    actual fun mint(goc: Long, seq: Long, sigHex: String): Boolean {
        return ledger?.mint(goc, seq, sigHex) ?: false
    }

    actual fun burn(goc: Long, seq: Long, sigHex: String): Boolean {
        return ledger?.burn(goc, seq, sigHex) ?: false
    }

    actual fun send(targetPubKey: String, goc: Long, seq: Long, sigHex: String): Boolean {
        return ledger?.send(targetPubKey, goc, seq, sigHex) ?: false
    }

    actual fun bytesToHex(bytes: ByteArray): String = dole.core.bytesToHex(bytes)
    actual fun hexToBytes(s: String): ByteArray = dole.core.hexToBytes(s)
    actual fun getPersonIdAsHex(pubKey: ByteArray): String = dole.core.getPersonIdAsHex(pubKey)

    actual fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean {
        return dole.core.verifyCardCertificate(pubKey, cert)
    }

    actual fun startBleAdvertising(storagePath: String): Boolean {
        return dole.core.startBleAdvertising(storagePath)
    }

    actual fun stopBleAdvertising() {
        dole.core.stopBleAdvertising()
    }

    actual fun setInternetEnabled(enabled: Boolean) {
        dole.core.setInternetEnabled(enabled)
    }

    actual fun setIrohEnabled(enabled: Boolean) {
        dole.core.setIrohEnabled(enabled)
    }

    actual fun connectedPeers(): List<PeerConnection> = dole.core.connectedPeers().map {
        PeerConnection(it.sessionId, it.ble, it.mdns, it.internet)
    }

    actual fun transportStatus(): TransportStatus = dole.core.transportStatus().let {
        TransportStatus(it.ble, it.iroh, it.internet)
    }
}
