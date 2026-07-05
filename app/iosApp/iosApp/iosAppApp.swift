import SwiftUI

@main
struct iosAppApp: App {
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ZStack {
                ContentView()
                if scenePhase != .active {
                    Rectangle().fill(.regularMaterial).ignoresSafeArea().transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.15), value: scenePhase)
        }
    }
}
