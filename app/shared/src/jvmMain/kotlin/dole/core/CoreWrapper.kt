package dole.core

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

    actual fun genesis(sigHex: String, certHex: String) {
        ledger?.genesis(sigHex, certHex)
    }

    actual fun mint(amount: Long, seq: Long, sigHex: String) {
        ledger?.mint(amount, seq, sigHex)
    }

    actual fun burn(amount: Long, seq: Long, sigHex: String) {
        ledger?.burn(amount, seq, sigHex)
    }

    actual fun send(targetPubKey: String, amount: Long, seq: Long, sigHex: String) {
        ledger?.send(targetPubKey, amount, seq, sigHex)
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
}