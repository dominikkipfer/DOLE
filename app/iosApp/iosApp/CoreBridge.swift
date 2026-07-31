import Foundation
import shared

typealias SharedNativeLedgerTransaction = shared.NativeLedgerTransaction

nonisolated private func rustBytesToHex(_ bytes: Data) -> String { bytesToHex(bytes: bytes) }
nonisolated private func rustHexToBytes(_ value: String) -> Data { hexToBytes(s: value) }
nonisolated private func rustGetPersonIdAsHex(_ pubKey: Data) -> String { getPersonIdAsHex(pubKey: pubKey) }
nonisolated private func rustVerifyCardCertificate(_ pubKey: Data, _ cert: Data) -> Bool { verifyCardCertificate(pubKey: pubKey, cert: cert) }
nonisolated private func rustPrepareGenesis(
    _ publicKeyId: String,
    _ publicKey: String,
    _ sigHex: String,
    _ certHex: String,
    _ timestamp: Int64?
) -> LedgerTransaction? {
    prepareGenesisTransaction(
        publicKeyId: publicKeyId,
        publicKey: publicKey,
        sigHex: sigHex,
        certHex: certHex,
        timestamp: timestamp
    )
}
nonisolated private func rustPrepareTransaction(
    _ publicKey: String,
    _ txType: String,
    _ targetId: String,
    _ goc: Int64,
    _ seq: Int64,
    _ sigHex: String,
    _ timestamp: Int64?
) -> LedgerTransaction? {
    prepareLedgerTransaction(
        publicKey: publicKey,
        txType: txType,
        targetId: targetId,
        goc: goc,
        seq: seq,
        sigHex: sigHex,
        timestamp: timestamp
    )
}
nonisolated private func rustBenchmarkTransactions() -> [LedgerTransaction] {
    benchGenerateTransactions()
}

nonisolated private func sharedTransaction(_ transaction: LedgerTransaction) -> SharedNativeLedgerTransaction {
    SharedNativeLedgerTransaction(
        id: transaction.id,
        txType: transaction.txType,
        author: transaction.author,
        publicKey: transaction.publicKey,
        certificate: transaction.certificate,
        targetId: transaction.targetId,
        goc: transaction.goc,
        seq: transaction.seq,
        timestamp: transaction.timestamp,
        signature: transaction.signature
    )
}

nonisolated final class CoreBridge: NSObject, IosCore, @unchecked Sendable {
    static func install() {
        CoreWrapperKt.installIosCore(core: CoreBridge())
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

    func prepareGenesisTransaction(
        publicKeyId: String,
        publicKey: String,
        sigHex: String,
        certHex: String,
        timestamp: KotlinLong?
    ) -> SharedNativeLedgerTransaction? {
        rustPrepareGenesis(publicKeyId, publicKey, sigHex, certHex, timestamp?.int64Value)
            .map(sharedTransaction)
    }

    func prepareLedgerTransaction(
        publicKey: String,
        txType: String,
        targetId: String,
        goc: Int64,
        seq: Int64,
        sigHex: String,
        timestamp: KotlinLong?
    ) -> SharedNativeLedgerTransaction? {
        rustPrepareTransaction(
            publicKey,
            txType,
            targetId,
            goc,
            seq,
            sigHex,
            timestamp?.int64Value
        ).map(sharedTransaction)
    }

    func benchGenerateTransactions() -> [SharedNativeLedgerTransaction] {
        rustBenchmarkTransactions().map(sharedTransaction)
    }
}
