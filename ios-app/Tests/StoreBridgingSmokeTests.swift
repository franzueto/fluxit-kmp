@testable import FluxIt
import Shared
import SwiftUI
import XCTest

// Phase 05 §12 / §15 — iOS SKIE bridging smoke (compile-level).
//
// Proves the shared MVI surface projects into idiomatic Swift via SKIE:
//   - Kotlin `sealed interface` State/Intent/Effect hierarchies become Swift
//     enums with EXHAUSTIVE `switch` (no `@unknown default`) through SKIE's
//     `onEnum(of:)`. The switches below compile only because every case is
//     present — that is the guarantee under test.
//   - Kotlin `enum class Tab` becomes a native Swift enum (CaseIterable).
//   - `state: StateFlow` / `effects: Flow` project as `AsyncSequence` and
//     `dispatch(intent:)` is callable — verified by the `compileCheck` helper
//     that type-checks `observe(_:into:)` + `dispatch` against a real store
//     type. A fully-wired store isn't constructible from Swift until Phase 06
//     DI lands (no in-memory repository is exported), so this asserts the
//     bridging SHAPE; the runtime end-to-end dispatch→effect lands then.
final class StoreBridgingSmokeTests: XCTestCase {
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
