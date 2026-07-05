import Foundation
import shared

enum CoreBridge {
    static func install() {
        CoreWrapperKt.swiftStartGlobalSyncAction = { path in
            startGlobalSync(storagePath: path)
        }

        CoreWrapperKt.swiftStopGlobalSyncAction = {
            stopGlobalSync()
        }

        CoreWrapperKt.swiftInitAction = { onStateUpdated, path, publicKeyId, publicKeyFull in
            AppEngine.shared.stateHandler = { balance, json in
                onStateUpdated(KotlinLong(value: balance), json)
            }
            AppEngine.shared.ledger = Ledger.initLedger(
                listener: AppEngine.shared,
                storagePath: path,
                publicKeyId: publicKeyId,
                publicKeyFull: publicKeyFull
            )
        }

        CoreWrapperKt.swiftShutdownAction = {
            AppEngine.shared.ledger?.shutdown()
            AppEngine.shared.ledger = nil
            AppEngine.shared.stateHandler = nil
        }

        CoreWrapperKt.swiftGenesisAction = { sigHex, certHex in
            AppEngine.shared.ledger?.genesis(sigHex: sigHex, certHex: certHex)
        }

        CoreWrapperKt.swiftMintAction = { amount, seq, sigHex in
            AppEngine.shared.ledger?.mint(
                delta: int64Value(amount),
                seq: int64Value(seq),
                sigHex: sigHex
            )
        }

        CoreWrapperKt.swiftBurnAction = { amount, seq, sigHex in
            AppEngine.shared.ledger?.burn(
                delta: int64Value(amount),
                seq: int64Value(seq),
                sigHex: sigHex
            )
        }

        CoreWrapperKt.swiftSendAction = { target, amount, seq, sigHex in
            AppEngine.shared.ledger?.send(
                targetPubKey: target,
                delta: int64Value(amount),
                seq: int64Value(seq),
                sigHex: sigHex
            )
        }

        CoreWrapperKt.swiftBytesToHexAction = { bytes in
            bytesToHex(bytes: data(from: bytes))
        }

        CoreWrapperKt.swiftHexToBytesAction = { value in
            kotlinByteArray(from: hexToBytes(s: value))
        }

        CoreWrapperKt.swiftGetPersonIdAsHexAction = { bytes in
            getPersonIdAsHex(pubKey: data(from: bytes))
        }

        CoreWrapperKt.swiftVerifyCardCertificateAction = { pubKey, cert in
            verifyCardCertificate(pubKey: data(from: pubKey), cert: data(from: cert))
        }

        CoreWrapperKt.swiftStartBleAdvertisingAction = { path in
            startBleAdvertising(storagePath: path)
        }

        CoreWrapperKt.swiftStopBleAdvertisingAction = {
            stopBleAdvertising()
        }
    }
}
