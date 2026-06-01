@testable import FluxIt
import Shared
import SwiftUI
import XCTest

// Phase 05 §12 / §15 — iOS SKIE bridging smoke (compile-level + runtime).
//
// `testRootStoreReachesReadyAtRuntime` is the §12 RUNTIME smoke (Slice C): it
// starts the real Koin graph (ADR-015) over a native SQLite driver, resolves
// `RootStore`, dispatches `AppStarted`, and observes `state` until startup
// completes — a true dispatch → use case → state round-trip across the SKIE
// boundary. The remaining tests assert the bridging SHAPE:
//   - Kotlin `sealed interface` State/Intent/Effect hierarchies become Swift
//     enums with EXHAUSTIVE `switch` (no `@unknown default`) through SKIE's
//     `onEnum(of:)`. The switches below compile only because every case is
//     present — that is the guarantee under test.
//   - Kotlin `enum class Tab` becomes a native Swift enum (CaseIterable).
//   - `state: StateFlow` / `effects: Flow` project as `AsyncSequence` and
//     `dispatch(intent:)` is callable — verified by the `compileCheck` helper
//     that type-checks `observe(_:into:)` + `dispatch` against a real store type.
final class StoreBridgingSmokeTests: XCTestCase {
    // §12 runtime smoke (Slice C): real graph, real driver, real round-trip.
    // Empty DB → InitializeApp rehydrates zero reminders (real iOS scheduler
    // returns Ok over an empty store) → init transitions Initializing → Ready.
    //
    // Phase 06 Slice 7: the iOS composition root (`FluxItApp.init`, the test
    // host's `@main App`) now starts Koin at launch and owns it for the process,
    // so the test no longer calls `doInitKoinIos()` / `stopKoinApp()` itself —
    // doing so would throw `KoinApplicationAlreadyStartedException`. It resolves
    // the already-started session `RootStore` and drives the same round-trip.
    func testRootStoreReachesReadyAtRuntime() async throws {
        let store = InitKoinKt.resolveRootStore()
        store.dispatch(intent: RootIntentAppStarted())

        var reachedReady = false
        for await state in store.state {
            if case .ready = onEnum(of: state.`init`) {
                reachedReady = true
                break
            }
        }
        XCTAssertTrue(reachedReady, "RootStore should reach InitState.Ready after AppStarted")
    }

    func testEffectSealedTypeIsExhaustiveSwiftEnum() {
        let effect = ListsEffectShowError(message: "boom")
        switch onEnum(of: effect) {
        case .navigateToCreateList:
            XCTFail("unexpected case")
        case .navigateToListDetail:
            XCTFail("unexpected case")
        case .navigateToTab:
            XCTFail("unexpected case")
        case let .showError(payload):
            XCTAssertEqual(payload.message, "boom")
        case .showUndoSnackbar:
            XCTFail("unexpected case")
        }
    }

    func testLoadStateSealedTypeIsExhaustiveSwiftEnum() {
        let state: LoadStateError = LoadStateError(message: "nope")
        switch onEnum(of: state) {
        case .loading:
            XCTFail("unexpected case")
        case .empty:
            XCTFail("unexpected case")
        case .loaded:
            XCTFail("unexpected case")
        case let .error(payload):
            XCTAssertEqual(payload.message, "nope")
        }
    }

    func testTabProjectsAsNativeSwiftEnum() {
        // Qualified as `Shared.Tab` — SwiftUI also ships a `Tab` type, so the
        // bare name is ambiguous once both modules are imported.
        let tab: Shared.Tab = .calendar
        switch tab {
        case .lists, .calendar, .starred, .account:
            break
        }
        // CaseIterable comes for free with the SKIE enum projection.
        XCTAssertEqual(Shared.Tab.allCases.count, 4)
    }

    func testEffectPayloadsConstructAndCarryData() {
        let snackbar = ListsEffectShowUndoSnackbar(name: "Groceries", secondsRemaining: 5)
        XCTAssertEqual(snackbar.name, "Groceries")
        XCTAssertEqual(snackbar.secondsRemaining, 5)

        let toTab = ListsEffectNavigateToTab(tab: .starred)
        XCTAssertEqual(toTab.tab, .starred)
    }

    // Not executed: it exists only so the Swift compiler type-checks that
    // `dispatch(intent:)` is callable and that `observe(_:into:)` accepts a real
    // store — i.e. that `state` projects as an AsyncSequence. If SKIE stopped
    // bridging the Flow surface, this would fail to compile and break the build.
    @MainActor
    private func compileCheck(_ store: ListsDashboardStore, _ binding: Binding<ListsState>) async {
        store.dispatch(intent: ListsIntentSearchQueryChanged(query: "milk"))
        // Module-qualified: NSObject (XCTestCase) has its own KVO `observe`.
        await FluxIt.observe(store, into: binding)
    }
}
