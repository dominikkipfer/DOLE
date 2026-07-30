import SwiftUI
import UIKit
import shared

@main
struct iosAppApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var stoppedScanForNewCard = false

    private let viewModel: WalletViewModel

    init() {
        CoreBridge.install()

        let documentDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        let storagePath = documentDirectory?.path ?? ""

        viewModel = WalletViewModelFactoryKt.createWalletViewModel(
            card: IosSmartCard.shared,
            storagePath: storagePath
        )

        viewModel.onNetworkPermissionsGranted()
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                ContentView(viewModel: viewModel)
                if scenePhase != .active {
                    Rectangle().fill(.regularMaterial).ignoresSafeArea()
                }
            }
            .onOpenURL { _ in
                handleCardActivation()
            }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                guard activity.webpageURL != nil else { return }
                handleCardActivation()
            }
            .task {
                await stopNativeScanWhenNewCardOverlayAppears()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                viewModel.onAppBackground()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                viewModel.onAppForeground()
            }
        }
    }

    private func handleCardActivation() {
        IosSmartCard.shared.beginScan()
    }

    @MainActor
    private func stopNativeScanWhenNewCardOverlayAppears() async {
        while !Task.isCancelled {
            if viewModel.isNewCardDetected {
                if !stoppedScanForNewCard {
                    stoppedScanForNewCard = true
                    IosSmartCard.shared.completeSession()
                }
            } else {
                stoppedScanForNewCard = false
            }

            try? await Task.sleep(for: .milliseconds(250))
        }
    }
}
