import Foundation
import shared

typealias SharedPeerConnection = shared.PeerConnection
typealias SharedTransportStatus = shared.TransportStatus

nonisolated private func rustStartGlobalSync(_ path: String) { startGlobalSync(storagePath: path) }
nonisolated private func rustStopGlobalSync() { stopGlobalSync() }
nonisolated private func rustBytesToHex(_ bytes: Data) -> String { bytesToHex(bytes: bytes) }
nonisolated private func rustHexToBytes(_ value: String) -> Data { hexToBytes(s: value) }
nonisolated private func rustGetPersonIdAsHex(_ pubKey: Data) -> String { getPersonIdAsHex(pubKey: pubKey) }
nonisolated private func rustVerifyCardCertificate(_ pubKey: Data, _ cert: Data) -> Bool { verifyCardCertificate(pubKey: pubKey, cert: cert) }
nonisolated private func rustResetLedger(_ path: String) -> Bool { resetLedger(storagePath: path) }
nonisolated private func rustStartBleAdvertising(_ path: String) -> Bool { startBleAdvertising(storagePath: path) }
nonisolated private func rustStopBleAdvertising() { stopBleAdvertising() }
nonisolated private func rustSetInternetEnabled(_ enabled: Bool) { setInternetEnabled(enabled: enabled) }
nonisolated private func rustSetIrohEnabled(_ enabled: Bool) { setIrohEnabled(enabled: enabled) }
nonisolated private func rustTransportStatus() -> SharedTransportStatus {
    let status = transportStatus()
    return SharedTransportStatus(ble: status.ble, iroh: status.iroh, internet: status.internet)
}
nonisolated private func rustConnectedPeers() -> [SharedPeerConnection] {
    connectedPeers().map {
        SharedPeerConnection(sessionId: $0.sessionId, ble: $0.ble, mdns: $0.mdns, internet: $0.internet)
    }
}

nonisolated final class CoreBridge: NSObject, IosCore, @unchecked Sendable {

    static func install() {
        CoreWrapperKt.installIosCore(core: CoreBridge())
    }

    func startGlobalSync(storagePath: String) {
        rustStartGlobalSync(storagePath)
    }

    func stopGlobalSync() {
        rustStopGlobalSync()
    }

    func doInitLedger(
        onStateUpdated: @escaping (KotlinLong, String) -> Void,
        storagePath: String,
        publicKeyId: String,
        publicKeyFull: String
    ) {
        AppEngine.shared.stateHandler = { balance, json in
            onStateUpdated(KotlinLong(value: balance), json)
        }
        AppEngine.shared.ledger = Ledger.initLedger(
            listener: AppEngine.shared,
            storagePath: storagePath,
            publicKeyId: publicKeyId,
            publicKeyFull: publicKeyFull
        )
    }

    func shutdown() {
        AppEngine.shared.ledger?.shutdown()
        AppEngine.shared.ledger = nil
        AppEngine.shared.stateHandler = nil
    }

    func resetLedger(storagePath: String) -> Bool {
        rustResetLedger(storagePath)
    }

    func genesis(sigHex: String, certHex: String) {
        AppEngine.shared.ledger?.genesis(sigHex: sigHex, certHex: certHex)
    }

    func mint(goc: Int64, seq: Int64, sigHex: String) -> Bool {
        AppEngine.shared.ledger?.mint(goc: goc, seq: seq, sigHex: sigHex) ?? false
    }

    func burn(goc: Int64, seq: Int64, sigHex: String) -> Bool {
        AppEngine.shared.ledger?.burn(goc: goc, seq: seq, sigHex: sigHex) ?? false
    }

    func send(targetPubKey: String, goc: Int64, seq: Int64, sigHex: String) -> Bool {
        AppEngine.shared.ledger?.send(targetPubKey: targetPubKey, goc: goc, seq: seq, sigHex: sigHex) ?? false
    }

    func bytesToHex(bytes: KotlinByteArray) -> String {
        rustBytesToHex(data(from: bytes))
    }

    func hexToBytes(s: String) -> KotlinByteArray {
        kotlinByteArray(from: rustHexToBytes(s))
    }

    func getPersonIdAsHex(pubKey: KotlinByteArray) -> String {
        rustGetPersonIdAsHex(data(from: pubKey))
    }

    func verifyCardCertificate(pubKey: KotlinByteArray, cert: KotlinByteArray) -> Bool {
        rustVerifyCardCertificate(data(from: pubKey), data(from: cert))
    }

    func startBleAdvertising(storagePath: String) -> Bool {
        rustStartBleAdvertising(storagePath)
    }

    func stopBleAdvertising() {
        rustStopBleAdvertising()
    }

    func setInternetEnabled(enabled: Bool) {
        rustSetInternetEnabled(enabled)
    }

    func setIrohEnabled(enabled: Bool) {
        rustSetIrohEnabled(enabled)
    }

    func connectedPeers() -> [SharedPeerConnection] {
        rustConnectedPeers()
    }

    func transportStatus() -> SharedTransportStatus {
        rustTransportStatus()
    }
}
