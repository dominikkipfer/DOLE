import shared

final class AppEngine: LedgerStateListener, @unchecked Sendable {
    static let shared = AppEngine()

    var ledger: Ledger?
    var stateHandler: ((Int64, String) -> Void)?

    private init() {}

    func onStateUpdated(balance: Int64, transactionHistoryJson: String) {
        stateHandler?(balance, transactionHistoryJson)
    }
}
