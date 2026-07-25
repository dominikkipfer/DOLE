import SwiftUI
import shared

struct ComposeView: UIViewControllerRepresentable {
    let viewModel: WalletViewModel

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(viewModel: viewModel)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
