import SwiftUI
import shared

struct ContentView: View {
    let viewModel: WalletViewModel

    var body: some View {
        ComposeView(viewModel: viewModel).ignoresSafeArea(.all)
    }
}
