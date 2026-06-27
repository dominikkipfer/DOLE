@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package dole.core

interface UIStateListener {
    fun onStateUpdated(balance: Long, historyJson: String)
}

expect object CoreWrapper {
    fun startGlobalSync(storagePath: String)
    fun stopGlobalSync()
    fun initLedger(listener: UIStateListener, storagePath: String, publicKeyId: String, publicKeyFull: String)
    fun shutdown()

    fun genesis(sigHex: String, certHex: String)
    fun mint(amount: Long, seq: Long, sigHex: String)
    fun burn(amount: Long, seq: Long, sigHex: String)
    fun send(targetPubKey: String, amount: Long, seq: Long, sigHex: String)

    fun bytesToHex(bytes: ByteArray): String
    fun hexToBytes(s: String): ByteArray
    fun getPersonIdAsHex(pubKey: ByteArray): String
    fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean
    fun startBleAdvertising(storagePath: String): Boolean
    fun stopBleAdvertising()
}