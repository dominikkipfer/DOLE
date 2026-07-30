package dole.core

import dole.viewmodel.PeerConnection
import dole.viewmodel.TransportStatus

interface IosCore {
	fun startGlobalSync(storagePath: String)
	fun stopGlobalSync()
	fun initLedger(onStateUpdated: (Long, String) -> Unit, storagePath: String, publicKeyId: String, publicKeyFull: String)
	fun shutdown()
	fun resetLedger(storagePath: String): Boolean
	fun genesis(sigHex: String, certHex: String)
	fun mint(goc: Long, seq: Long, sigHex: String): Boolean
	fun burn(goc: Long, seq: Long, sigHex: String): Boolean
	fun send(targetPubKey: String, goc: Long, seq: Long, sigHex: String): Boolean
	fun bytesToHex(bytes: ByteArray): String
	fun hexToBytes(s: String): ByteArray
	fun getPersonIdAsHex(pubKey: ByteArray): String
	fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean
	fun startBleAdvertising(storagePath: String): Boolean
	fun stopBleAdvertising()
	fun setInternetEnabled(enabled: Boolean)
	fun setIrohEnabled(enabled: Boolean)
	fun localSessionId(): String
	fun connectedPeers(): List<PeerConnection>
	fun transportStatus(): TransportStatus
	fun benchSetMode(enabled: Boolean)
	fun benchModeEnabled(): Boolean
	fun benchGenerateWorkload(storagePath: String): Int
	fun benchRunStore(storagePath: String): Int
	fun benchRunLatency(storagePath: String): Boolean
	fun benchLatencyReport(): String?
}

private var iosCore: IosCore? = null

fun installIosCore(core: IosCore) {
	iosCore = core
}

actual object CoreWrapper {
	actual val isBleSupported: Boolean = false

	actual fun startGlobalSync(storagePath: String) {
		iosCore?.startGlobalSync(storagePath)
	}

	actual fun stopGlobalSync() {
		iosCore?.stopGlobalSync()
	}

	actual fun initLedger(onStateUpdated: (Long, String) -> Unit, storagePath: String, publicKeyId: String, publicKeyFull: String) {
		iosCore?.initLedger(onStateUpdated, storagePath, publicKeyId, publicKeyFull)
	}

	actual fun shutdown() {
		iosCore?.shutdown()
	}

	actual fun resetLedger(storagePath: String): Boolean {
		return iosCore?.resetLedger(storagePath) ?: false
	}

	actual fun genesis(sigHex: String, certHex: String) {
		iosCore?.genesis(sigHex, certHex)
	}

	actual fun mint(goc: Long, seq: Long, sigHex: String): Boolean {
		return iosCore?.mint(goc, seq, sigHex) ?: false
	}

	actual fun burn(goc: Long, seq: Long, sigHex: String): Boolean {
		return iosCore?.burn(goc, seq, sigHex) ?: false
	}

	actual fun send(targetPubKey: String, goc: Long, seq: Long, sigHex: String): Boolean {
		return iosCore?.send(targetPubKey, goc, seq, sigHex) ?: false
	}

	actual fun bytesToHex(bytes: ByteArray): String {
		return iosCore?.bytesToHex(bytes) ?: ""
	}

	actual fun hexToBytes(s: String): ByteArray {
		return iosCore?.hexToBytes(s) ?: ByteArray(0)
	}

	actual fun getPersonIdAsHex(pubKey: ByteArray): String {
		return iosCore?.getPersonIdAsHex(pubKey) ?: ""
	}

	actual fun verifyCardCertificate(pubKey: ByteArray, cert: ByteArray): Boolean {
		return iosCore?.verifyCardCertificate(pubKey, cert) ?: false
	}

	actual fun startBleAdvertising(storagePath: String): Boolean {
		return iosCore?.startBleAdvertising(storagePath) ?: false
	}

	actual fun stopBleAdvertising() {
		iosCore?.stopBleAdvertising()
	}

	actual fun setInternetEnabled(enabled: Boolean) {
		iosCore?.setInternetEnabled(enabled)
	}

	actual fun setIrohEnabled(enabled: Boolean) {
		iosCore?.setIrohEnabled(enabled)
	}

	actual fun localSessionId(): String = iosCore?.localSessionId() ?: ""

	actual fun connectedPeers(): List<PeerConnection> {
		return iosCore?.connectedPeers() ?: emptyList()
	}

	actual fun transportStatus(): TransportStatus {
		return iosCore?.transportStatus() ?: TransportStatus(false, false, false)
	}

	actual fun benchSetMode(enabled: Boolean) {
		iosCore?.benchSetMode(enabled)
	}

	actual fun benchModeEnabled(): Boolean = iosCore?.benchModeEnabled() ?: false

	actual fun benchGenerateWorkload(storagePath: String): Int =
		iosCore?.benchGenerateWorkload(storagePath) ?: 0

	actual fun benchRunStore(storagePath: String): Int =
		iosCore?.benchRunStore(storagePath) ?: 0

	actual fun benchRunLatency(storagePath: String): Boolean =
		iosCore?.benchRunLatency(storagePath) ?: false

	actual fun benchLatencyReport(): String? = iosCore?.benchLatencyReport()
}
