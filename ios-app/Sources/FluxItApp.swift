import SwiftUI

@main
struct FluxItApp: App {
    var body: some Scene {
        WindowGroup {
            // ADR-005b: v1 is dark-only. Lock the color scheme at the app root
            // so SwiftUI doesn't auto-resolve light variants of surfaces that
            // were authored for dark contrast in tokens.json.
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
