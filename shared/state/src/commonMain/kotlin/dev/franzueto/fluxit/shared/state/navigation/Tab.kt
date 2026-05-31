package dev.franzueto.fluxit.shared.state.navigation

/**
 * The four bottom-bar tabs (ADR-004). All four render in v1 to preserve the
 * design, but only [Lists] and [Account] are functional — [Calendar] and
 * [Starred] route to a "Coming soon" placeholder.
 *
 * Shared across stores: [RootStore][dev.franzueto.fluxit.shared.state.store.RootStore]
 * owns the selected tab; `ListsDashboardStore` emits `NavigateToTab` for the
 * placeholder tabs. A plain `enum` (SKIE projects Kotlin enums as Swift enums
 * directly, so SwiftUI gets an exhaustive `switch` for free).
 */
public enum class Tab {
    Lists,
    Calendar,
    Starred,
    Account,
}
