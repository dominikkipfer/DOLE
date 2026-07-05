import SwiftUI
import shared

struct ComposeView: UIViewControllerRepresentable {
    private let card = IosSmartCard()

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(card: card)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
