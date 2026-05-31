import Shared
import SwiftUI

// Phase 05 §3 — SwiftUI ↔ MVI store bridging helper.
//
// SKIE projects a store's `state: StateFlow<S>` as a Swift `AsyncSequence`, so a
// view can drive a `@State`/`Binding` straight from the store without scattering
// `.task { for await … }` boilerplate across every screen. This helper lives in
// the app (not the shared framework) per the plan: it is a SwiftUI concern.
//
// Stores are generic only through `BaseStore<S, I, E>` (the SKIE-exposed Store
// protocol is non-generic at the Obj-C boundary), so the helper is written
// against the base class — every concrete store (`ListsDashboardStore`,
// `RootStore`, …) is a `BaseStore` and binds through this same call.
@MainActor
func observe<S: AnyObject, I: AnyObject, E: AnyObject>(
    _ store: BaseStore<S, I, E>,
    into binding: Binding<S>
) async {
    for await state in store.state {
        binding.wrappedValue = state
    }
}
