import SwiftUI

struct ContentView: View {
    init() {
        CoreBridge.install()
    }

    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
