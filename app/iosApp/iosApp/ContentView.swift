import SwiftUI
import shared

// 1. Übersetzer für den Kotlin Listener
class SwiftLedgerListener: LedgerStateListener {
    let kotlinListener: UIStateListener
    
    init(kotlinListener: UIStateListener) {
        self.kotlinListener = kotlinListener
    }
    
    func onStateUpdated(balance: Int32, transactionHistoryJson: String) {
        kotlinListener.onStateUpdated(balance: Int32(balance), historyJson: transactionHistoryJson)
    }
}

// 2. Ein sicherer Speicherort für deine laufende Rust-Engine
class AppEngine {
    static let shared = AppEngine()
    var engine: PrototypeEngine?
}

// 3. Kotlin UI Container
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// 4. Das Haupt-UI
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

        // Mint
        CoreWrapperKt.swiftMintAction = { amount in
            try? AppEngine.shared.engine?.mint(amount: Int32(truncating: amount))
        }

        // Burn
        CoreWrapperKt.swiftBurnAction = { amount in
            try? AppEngine.shared.engine?.burn(amount: Int32(truncating: amount))
        }

        // Send
        CoreWrapperKt.swiftSendAction = { target, amount in
            try? AppEngine.shared.engine?.send(targetPubKey: target, amount: Int32(truncating: amount))
        }
    }
}
