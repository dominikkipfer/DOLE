@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
package dole.core

var swiftInitAction: ((UIStateListener, String) -> Unit)? = null
var swiftMintAction: ((Int) -> Unit)? = null
var swiftBurnAction: ((Int) -> Unit)? = null
var swiftSendAction: ((String, Int) -> Unit)? = null

var swiftBytesToHexAction: ((ByteArray) -> String)? = null
var swiftHexToBytesAction: ((String) -> ByteArray)? = null
var swiftSha256Action: ((ByteArray) -> ByteArray)? = null
var swiftGetPersonIdAsHexAction: ((ByteArray) -> String)? = null

actual object CoreWrapper {

	actual fun startGlobalSync(storagePath: String) = Unit

	actual fun stopGlobalSync() = Unit

	actual fun initLedger(listener: UIStateListener, storagePath: String, publicKeyId: String, publicKeyFull: String) {
		swiftInitAction?.invoke(listener, storagePath)
	}

	actual fun shutdown() = Unit

	actual fun genesis(sigHex: String, certHex: String) = Unit

	actual fun mint(amount: Long, seq: Long, sigHex: String) { swiftMintAction?.invoke(amount.toInt()) }

	actual fun burn(amount: Long, seq: Long, sigHex: String) { swiftBurnAction?.invoke(amount.toInt()) }

	actual fun send(targetPubKey: String, amount: Long, seq: Long, sigHex: String) {
		swiftSendAction?.invoke(targetPubKey, amount.toInt())
	}

	actual fun bytesToHex(bytes: ByteArray): String {
		return swiftBytesToHexAction?.invoke(bytes) ?: ""
	}

	actual fun hexToBytes(s: String): ByteArray {
		return swiftHexToBytesAction?.invoke(s) ?: ByteArray(0)
	}

	actual fun sha256(input: ByteArray): ByteArray {
		return swiftSha256Action?.invoke(input) ?: ByteArray(0)
	}

	actual fun getPersonIdAsHex(pubKey: ByteArray): String {
		return swiftGetPersonIdAsHexAction?.invoke(pubKey) ?: ""
	}

	actual fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean = false

	actual fun startBleAdvertising(storagePath: String): Boolean = false

	actual fun stopBleAdvertising() = Unit
}