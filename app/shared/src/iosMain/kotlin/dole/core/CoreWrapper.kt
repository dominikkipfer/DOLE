package dole.core

var swiftStartGlobalSyncAction: ((String) -> Unit)? = null
var swiftStopGlobalSyncAction: (() -> Unit)? = null
var swiftInitAction: (((Long, String) -> Unit, String, String, String) -> Unit)? = null
var swiftShutdownAction: (() -> Unit)? = null
var swiftGenesisAction: ((String, String) -> Unit)? = null
var swiftMintAction: ((Long, Long, String) -> Unit)? = null
var swiftBurnAction: ((Long, Long, String) -> Unit)? = null
var swiftSendAction: ((String, Long, Long, String) -> Unit)? = null

var swiftBytesToHexAction: ((ByteArray) -> String)? = null
var swiftHexToBytesAction: ((String) -> ByteArray)? = null
var swiftGetPersonIdAsHexAction: ((ByteArray) -> String)? = null
var swiftVerifyCardCertificateAction: ((ByteArray, ByteArray) -> Boolean)? = null
var swiftStartBleAdvertisingAction: ((String) -> Boolean)? = null
var swiftStopBleAdvertisingAction: (() -> Unit)? = null

actual object CoreWrapper {

	actual fun startGlobalSync(storagePath: String) {
		swiftStartGlobalSyncAction?.invoke(storagePath)
	}

	actual fun stopGlobalSync() {
		swiftStopGlobalSyncAction?.invoke()
	}

	actual fun initLedger(onStateUpdated: (Long, String) -> Unit, storagePath: String, publicKeyId: String, publicKeyFull: String) {
		swiftInitAction?.invoke(onStateUpdated, storagePath, publicKeyId, publicKeyFull)
	}

	actual fun shutdown() {
		swiftShutdownAction?.invoke()
	}

	actual fun genesis(sigHex: String, certHex: String) {
		swiftGenesisAction?.invoke(sigHex, certHex)
	}

	actual fun mint(amount: Long, seq: Long, sigHex: String) {
		swiftMintAction?.invoke(amount, seq, sigHex)
	}

	actual fun burn(amount: Long, seq: Long, sigHex: String) {
		swiftBurnAction?.invoke(amount, seq, sigHex)
	}

	actual fun send(targetPubKey: String, amount: Long, seq: Long, sigHex: String) {
		swiftSendAction?.invoke(targetPubKey, amount, seq, sigHex)
	}

	actual fun bytesToHex(bytes: ByteArray): String {
		return swiftBytesToHexAction?.invoke(bytes) ?: ""
	}

	actual fun hexToBytes(s: String): ByteArray {
		return swiftHexToBytesAction?.invoke(s) ?: ByteArray(0)
	}

	actual fun getPersonIdAsHex(pubKey: ByteArray): String {
		return swiftGetPersonIdAsHexAction?.invoke(pubKey) ?: ""
	}

	actual fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean {
		return swiftVerifyCardCertificateAction?.invoke(pubKey, cert) ?: false
	}

	actual fun startBleAdvertising(storagePath: String): Boolean {
		return swiftStartBleAdvertisingAction?.invoke(storagePath) ?: false
	}

	actual fun stopBleAdvertising() {
		swiftStopBleAdvertisingAction?.invoke()
	}
}