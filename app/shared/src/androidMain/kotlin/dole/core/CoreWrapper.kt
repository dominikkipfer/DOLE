@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package dole.core

actual object CoreWrapper {
    private var ledger: Ledger? = null

    actual fun startGlobalSync(storagePath: String) {
        dole.core.startGlobalSync(storagePath)
    }

    actual fun stopGlobalSync() {
        dole.core.stopGlobalSync()
    }

    actual fun initLedger(listener: UIStateListener, storagePath: String, publicKeyId: String, publicKeyFull: String) {
        val rustListener = object : LedgerStateListener {
            override fun onStateUpdated(balance: Long, transactionHistoryJson: String) {
                listener.onStateUpdated(balance, transactionHistoryJson)
            }
            override fun onError(message: String) { // NEU
                listener.onError(message)
            }
        }
        ledger = Ledger.initLedger(rustListener, storagePath, publicKeyId, publicKeyFull)
    }

    actual fun shutdown() {
        ledger?.shutdown()
        ledger = null
    }

    actual fun genesis(fullPubKeyHex: String, sigHex: String, certHex: String) {
        ledger?.genesis(fullPubKeyHex, sigHex, certHex)
    }

    actual fun mint(amount: Long, sigHex: String) {
        ledger?.mint(amount, sigHex)
    }

    actual fun burn(amount: Long, sigHex: String) {
        ledger?.burn(amount, sigHex)
    }

    actual fun send(targetPubKey: String, amount: Long, sigHex: String) {
        ledger?.send(targetPubKey, amount, sigHex)
    }

    actual fun bytesToHex(bytes: ByteArray): String = dole.core.bytesToHex(bytes)
    actual fun hexToBytes(s: String): ByteArray = dole.core.hexToBytes(s)
    actual fun getPersonIdAsHex(pubKey: ByteArray): String = dole.core.getPersonIdAsHex(pubKey)

    actual fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean {
        return dole.core.verifyCardCertificate(pubKey, cert)
    }
}