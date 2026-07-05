import Foundation
import shared

final class IosSmartCard: NSObject, SmartCard {
    var isConnected: Bool { false }
    var isMinter: Bool { false }
    var isPinSet: Bool { false }
    var isGenesisDone: Bool { false }
    var pinRetries: Int32 { 0 }
    var publicKey: KotlinByteArray? { nil }
    var certificate: KotlinByteArray? { nil }

    func connect() {}
    func disconnect() {}
    func verifyPin(pin: KotlinByteArray) -> Bool { false }
    func changePin(newPin: KotlinByteArray) -> Bool { false }
    func processGenesis() -> KotlinByteArray { KotlinByteArray(size: 0) }
    func processMint(payload: KotlinByteArray) -> KotlinByteArray { KotlinByteArray(size: 0) }
    func processBurn(payload: KotlinByteArray) -> KotlinByteArray { KotlinByteArray(size: 0) }
    func processSend(payload: KotlinByteArray) -> KotlinByteArray { KotlinByteArray(size: 0) }
    func processReceive(payload: KotlinByteArray) {}
    func addPeer(payload: KotlinByteArray) {}
}
