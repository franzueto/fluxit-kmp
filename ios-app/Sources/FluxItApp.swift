import SwiftUI
import Shared

@main
struct FluxItApp: App {
    init() {
        // The iOS composition root (Phase 06 Slice 7, ADR-015). Start the single
        // Koin graph once at process launch over the native SqlDriver + real iOS
        // platform actuals. Kotlin/Native prefixes `init*` top-level funcs with
        // `do` to dodge the Obj-C initializer clash, so `initKoinIos()` surfaces
        // as `doInitKoinIos()`.
        InitKoinIosKt.doInitKoinIos()
    }

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
