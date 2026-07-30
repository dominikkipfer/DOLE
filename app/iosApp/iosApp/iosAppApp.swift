import SwiftUI
import UIKit
import UserNotifications
import shared

@main
struct iosAppApp: App {
    @Environment(\.scenePhase) private var scenePhase

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
                IosSmartCard.shared.beginScan()
            }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                guard activity.webpageURL != nil else { return }
                IosSmartCard.shared.beginScan()
            }
            .onChange(of: scenePhase) { phase in
                if phase == .active {
                    UNUserNotificationCenter.current().removeAllDeliveredNotifications()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                viewModel.onAppBackground()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                viewModel.onAppForeground()
            }
        }
    }
}
