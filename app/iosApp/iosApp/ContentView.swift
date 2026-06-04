import SwiftUI
import shared

class SwiftLedgerListener: LedgerStateListener {
    let kotlinListener: UIStateListener

    init(kotlinListener: UIStateListener) {
        self.kotlinListener = kotlinListener
    }

    func onStateUpdated(balance: Int32, transactionHistoryJson: String) {
        kotlinListener.onStateUpdated(balance: Int32(balance), historyJson: transactionHistoryJson)
    }
}

class AppEngine {
    static let shared = AppEngine()
    var engine: PrototypeEngine?
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    init() {
        setupBridge()
    }

    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }

    private func setupBridge() {
        CoreWrapperKt.swiftInitAction = { listener, path in
            let swiftListener = SwiftLedgerListener(kotlinListener: listener)
            AppEngine.shared.engine = try? PrototypeEngine.initPrototype(listener: swiftListener, storagePath: path)
        }

        CoreWrapperKt.swiftMintAction = { amount in
            try? AppEngine.shared.engine?.mint(amount: Int32(truncating: amount))
        }

        CoreWrapperKt.swiftBurnAction = { amount in
            try? AppEngine.shared.engine?.burn(amount: Int32(truncating: amount))
        }

        CoreWrapperKt.swiftSendAction = { target, amount in
            try? AppEngine.shared.engine?.send(targetPubKey: target, amount: Int32(truncating: amount))
        }
    }
}
