import SwiftUI
import Shared

/// The iOS composition-root view (Phase 06 Slice 7). Resolves the session-scoped
/// `RootStore` from Koin, runs `InitializeApp` once via `AppStarted`, and gates a
/// `NavigationStack` on the resulting `InitState`:
///  - `.initializing` → a splash spinner.
///  - `.failed` → a minimal retry surface (polished splash UX is a later phase).
///  - `.ready` → the Lists Dashboard.
struct ContentView: View {
    private let store = InitKoinKt.resolveRootStore()
    @State private var state: RootState = RootState(init: InitStateInitializing(), currentTab: .lists)

    var body: some View {
        Group {
            switch onEnum(of: state.`init`) {
            case .initializing:
                ProgressView()
            case let .failed(failure):
                VStack(spacing: 12) {
                    Text(failure.message)
                    Button("Retry") { store.dispatch(intent: RootIntentAppStarted()) }
                }
            case .ready:
                NavigationStack {
                    ListsDashboardView()
                        .navigationTitle("Lists")
                }
            }
        }
        .task {
            store.dispatch(intent: RootIntentAppStarted())
            await observe(store, into: $state)
        }
    }
}
