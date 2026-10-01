import SwiftUI
import UIKit
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        #if FLUXIT_PARITY
        if ProcessInfo.processInfo.arguments.contains("-FluxItDefaultGraphCheck") || ProcessInfo.processInfo.arguments.contains("-FluxItSessionCleanupCheck") {
            // The default-config graph probe must not compose the auth/session UI.
            Color.clear
        } else {
            ComposeView()
                .ignoresSafeArea(.keyboard)
        }
        #else
        ComposeView()
            .ignoresSafeArea(.keyboard) // Compose handles the keyboard itself
        #endif
    }
}
