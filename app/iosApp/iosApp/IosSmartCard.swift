@preconcurrency import CoreNFC
import Foundation
import shared

nonisolated final class IosSmartCard: NSObject, SmartCard, NFCTagReaderSessionDelegate, @unchecked Sendable {

    static let shared = IosSmartCard()

    private static let appletAid = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x01])
    private static let claProprietary: UInt8 = 0x80

    private enum Op {
        static let genesis: UInt8 = 0x00
        static let mint: UInt8 = 0x01
        static let burn: UInt8 = 0x02
        static let send: UInt8 = 0x03
        static let receive: UInt8 = 0x04
        static let addPeer: UInt8 = 0x05
        static let getPubkey: UInt8 = 0x10
        static let verifyPin: UInt8 = 0x20
        static let changePin: UInt8 = 0x21
        static let getCert: UInt8 = 0x30
        static let getStatus: UInt8 = 0x60
        static let getSecureStatus: UInt8 = 0x61
        static let getPeerState: UInt8 = 0x62
    }

    private enum Status {
        static let pinEpochOffset = 8
        static let pinEpochSize = 4
        static let secureBalanceOffset = 0
        static let secureSeqOffset = 8
        static let secureSize = 16
        static let peerReceivedOffset = 0
        static let peerSentOffset = 8
        static let peerSize = 16
    }

    private static let sessionExtendingOps: Set<UInt8> = [
        Op.genesis, Op.mint, Op.burn, Op.send, Op.receive, Op.addPeer,
        Op.verifyPin, Op.changePin, Op.getPeerState
    ]
    private static let idleCloseDelay: TimeInterval = 5

    private enum CardError: LocalizedError {
        case notFound
        case invalidResponse
        case badStatusWord(UInt16)

        var errorDescription: String? {
            switch self {
            case .notFound: return "No card found. Please insert your smart card."
            case .invalidResponse: return "Invalid response length"
            case .badStatusWord(let sw): return "Card Error SW: " + String(format: "%04x", sw)
            }
        }
    }

    private let lock = NSLock()
    private let idleQueue = DispatchQueue(label: "dole.nfc.idle")
    private var session: NFCTagReaderSession?
    private var tag: (any NFCISO7816Tag)?
    private var appletSelected = false
    private var connectWaiters: [DispatchSemaphore] = []
    private var idleCloseWork: DispatchWorkItem?

    func beginScan() {
        guard NFCReaderSession.readingAvailable else {
            failWaiters()
            return
        }
        lock.lock()
        if session != nil {
            lock.unlock()
            return
        }
        guard let newSession = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: nil) else {
            lock.unlock()
            failWaiters()
            return
        }
        newSession.alertMessage = "Hold your DOLE card near the top of the iPhone."
        session = newSession
        lock.unlock()
        newSession.begin()
    }

    private func failWaiters() {
        lock.lock()
        let waiters = connectWaiters
        connectWaiters = []
        lock.unlock()
        waiters.forEach { $0.signal() }
    }

    private func scheduleIdleClose() {
        lock.lock()
        idleCloseWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let current = self.session
            self.session = nil
            self.tag = nil
            self.appletSelected = false
            let waiters = self.connectWaiters
            self.connectWaiters = []
            self.lock.unlock()
            waiters.forEach { $0.signal() }
            current?.invalidate()
        }
        idleCloseWork = work
        lock.unlock()
        idleQueue.asyncAfter(deadline: .now() + Self.idleCloseDelay, execute: work)
    }

    private func handleTagLoss() {
        lock.lock()
        tag = nil
        appletSelected = false
        let current = session
        lock.unlock()
        current?.alertMessage = "Connection lost. Hold the card still."
        current?.restartPolling()
    }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: any Error) {
        lock.lock()
        var waiters: [DispatchSemaphore] = []
        if self.session === session {
            self.session = nil
            self.tag = nil
            self.appletSelected = false
            waiters = connectWaiters
            connectWaiters = []
            idleCloseWork?.cancel()
        }
        lock.unlock()
        waiters.forEach { $0.signal() }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        if tags.count > 1 {
            session.alertMessage = "More than one card detected. Please present only one."
            session.restartPolling()
            return
        }
        guard let first = tags.first else { return }
        guard case .iso7816(let isoTag) = first else {
            session.invalidate(errorMessage: "This is not a DOLE card.")
            return
        }
        nonisolated(unsafe) let card = isoTag
        nonisolated(unsafe) let readerSession = session
        session.connect(to: first) { [weak self] error in
            guard let self else { return }
            if error != nil {
                readerSession.restartPolling()
                return
            }
            self.selectApplet(card, session: readerSession)
        }
    }

    private func selectApplet(_ isoTag: any NFCISO7816Tag, session: NFCTagReaderSession) {
        let select = Self.buildApdu(cla: 0x00, ins: 0xA4, p1: 0x04, data: Self.appletAid)
        guard let apdu = NFCISO7816APDU(data: select) else {
            session.invalidate(errorMessage: "Internal error.")
            return
        }
        nonisolated(unsafe) let card = isoTag
        nonisolated(unsafe) let readerSession = session
        isoTag.sendCommand(apdu: apdu) { [weak self] _, sw1, sw2, error in
            guard let self else { return }
            let sw = UInt16(sw1) << 8 | UInt16(sw2)
            if error != nil || sw != 0x9000 {
                readerSession.invalidate(errorMessage: "DOLE applet not found on this card.")
                return
            }
            self.lock.lock()
            self.tag = card
            self.appletSelected = true
            let waiters = self.connectWaiters
            self.connectWaiters = []
            self.lock.unlock()
            readerSession.alertMessage = "Card detected. Hold still…"
            self.scheduleIdleClose()
            waiters.forEach { $0.signal() }
        }
    }

    private static func buildApdu(cla: UInt8, ins: UInt8, p1: UInt8, data: Data?) -> Data {
        var apdu = Data([cla, ins, p1, 0x00, UInt8(data?.count ?? 0)])
        if let data { apdu.append(data) }
        return apdu
    }

    private func transceive(_ command: Data) throws -> Data {
        lock.lock()
        let currentTag = tag
        lock.unlock()
        guard let currentTag, currentTag.isAvailable else { throw CardError.notFound }
        guard let apdu = NFCISO7816APDU(data: command) else { throw CardError.invalidResponse }

        let semaphore = DispatchSemaphore(value: 0)
        var response: Data?
        var failure: (any Error)?
        currentTag.sendCommand(apdu: apdu) { data, sw1, sw2, error in
            if let error {
                failure = error
            } else {
                var full = data
                full.append(sw1)
                full.append(sw2)
                response = full
            }
            semaphore.signal()
        }
        if semaphore.wait(timeout: .now() + 20) == .timedOut {
            handleTagLoss()
            throw CardError.notFound
        }
        guard failure == nil, let response else {
            handleTagLoss()
            throw CardError.notFound
        }
        return response
    }

    private func checkStatusWord(_ response: Data) throws {
        guard response.count >= 2 else { throw CardError.invalidResponse }
        let trailer = [UInt8](response.suffix(2))
        let sw = UInt16(trailer[0]) << 8 | UInt16(trailer[1])
        if sw != 0x9000 { throw CardError.badStatusWord(sw) }
    }

    private func transmit(ins: UInt8, data: Data?) throws -> Data {
        guard isConnected else { throw CardError.notFound }
        let response = try transceive(Self.buildApdu(cla: Self.claProprietary, ins: ins, p1: 0x00, data: data))
        try checkStatusWord(response)
        if Self.sessionExtendingOps.contains(ins) {
            scheduleIdleClose()
        }
        return response.count > 2 ? Data(response.dropLast(2)) : Data()
    }

    private func statusBytes() -> [UInt8]? {
        guard let data = try? transmit(ins: Op.getStatus, data: nil) else { return nil }
        return [UInt8](data)
    }

    var isConnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        guard session != nil, appletSelected, let tag else { return false }
        return tag.isAvailable
    }

    func probe() -> Bool { isConnected }

    func connect() throws {
        if isConnected { return }
        guard NFCReaderSession.readingAvailable else { throw CardError.notFound }
        if Thread.isMainThread { throw CardError.notFound }

        let semaphore = DispatchSemaphore(value: 0)
        lock.lock()
        connectWaiters.append(semaphore)
        let existing = session
        lock.unlock()

        if let existing {
            existing.restartPolling()
        } else {
            beginScan()
        }

        _ = semaphore.wait(timeout: .now() + 60)
        if !isConnected { throw CardError.notFound }
    }

    func disconnect() {
        lock.lock()
        let hadTag = tag != nil
        tag = nil
        appletSelected = false
        var toInvalidate: NFCTagReaderSession?
        if hadTag {
            toInvalidate = session
            session = nil
            idleCloseWork?.cancel()
        }
        lock.unlock()
        toInvalidate?.invalidate()
    }

    func completeSession() {
        lock.lock()
        let current = session
        session = nil
        tag = nil
        appletSelected = false
        idleCloseWork?.cancel()
        let waiters = connectWaiters
        connectWaiters = []
        lock.unlock()
        waiters.forEach { $0.signal() }
        current?.invalidate()
    }

    var isMinter: Bool { statusBytes().map { $0.count > 0 && $0[0] == 0x01 } ?? false }
    var isPinSet: Bool { statusBytes().map { $0.count > 1 && $0[1] == 0x01 } ?? false }
    var isGenesisDone: Bool { statusBytes().map { $0.count > 2 && $0[2] == 0x01 } ?? false }

    var pinRetries: Int32 {
        guard let status = statusBytes() else { return 3 }
        return status.count > 3 ? Int32(status[3]) : 3
    }

    var pinEpoch: Int32 {
        let offset = Status.pinEpochOffset
        guard let status = statusBytes(), status.count >= offset + Status.pinEpochSize else { return -1 }

        var value: Int32 = 0
        for i in 0..<Status.pinEpochSize { value = (value << 8) | Int32(status[offset + i]) }
        return value
    }

    func secureState() -> CardSecureState? {
        guard let data = try? transmit(ins: Op.getSecureStatus, data: nil) else { return nil }
        let bytes = [UInt8](data)
        guard bytes.count >= Status.secureSize else { return nil }

        func longAt(_ offset: Int) -> Int64 {
            var value: Int64 = 0
            for i in 0..<8 { value = (value << 8) | Int64(bytes[offset + i]) }
            return value
        }
        return CardSecureState(balance: longAt(Status.secureBalanceOffset), seq: longAt(Status.secureSeqOffset))
    }

    func peerState(publicKey: KotlinByteArray) -> CardPeerState? {
        guard let response = try? transmit(ins: Op.getPeerState, data: data(from: publicKey)) else { return nil }
        let bytes = [UInt8](response)
        guard bytes.count >= Status.peerSize else { return nil }

        func longAt(_ offset: Int) -> Int64 {
            var value: Int64 = 0
            for i in 0..<8 { value = (value << 8) | Int64(bytes[offset + i]) }
            return value
        }
        return CardPeerState(received: longAt(Status.peerReceivedOffset), sent: longAt(Status.peerSentOffset))
    }

    var publicKey: KotlinByteArray? {
        guard let bytes = try? transmit(ins: Op.getPubkey, data: nil) else { return nil }
        return kotlinByteArray(from: bytes)
    }

    var certificate: KotlinByteArray? {
        guard let bytes = try? transmit(ins: Op.getCert, data: nil) else { return nil }
        return kotlinByteArray(from: bytes)
    }

    func verifyPin(pin: KotlinByteArray) -> Bool {
        (try? transmit(ins: Op.verifyPin, data: data(from: pin))) != nil
    }

    func changePin(newPin: KotlinByteArray) -> Bool {
        (try? transmit(ins: Op.changePin, data: data(from: newPin))) != nil
    }

    func processGenesis() throws -> KotlinByteArray {
        kotlinByteArray(from: try transmit(ins: Op.genesis, data: Data()))
    }

    func processMint(payload: KotlinByteArray) throws -> KotlinByteArray {
        kotlinByteArray(from: try transmit(ins: Op.mint, data: data(from: payload)))
    }

    func processBurn(payload: KotlinByteArray) throws -> KotlinByteArray {
        kotlinByteArray(from: try transmit(ins: Op.burn, data: data(from: payload)))
    }

    func processSend(payload: KotlinByteArray) throws -> KotlinByteArray {
        kotlinByteArray(from: try transmit(ins: Op.send, data: data(from: payload)))
    }

    func processReceive(payload: KotlinByteArray) throws {
        _ = try transmit(ins: Op.receive, data: data(from: payload))
    }

    func addPeer(payload: KotlinByteArray) throws {
        _ = try transmit(ins: Op.addPeer, data: data(from: payload))
    }
}
