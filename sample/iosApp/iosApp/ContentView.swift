import SwiftUI
import ComposeApp

/// The entire iOS side of the sample. Everything else is `commonMain`.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            // Compose handles its own insets; the viewer draws under the status bar on purpose.
            .ignoresSafeArea(.all)
    }
}
