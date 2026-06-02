import Shared
import SwiftUI

/// The Lists Dashboard (plan/07 §3/§5), wired to `ListsDashboardStore`. Mirrors
/// the Android `DashboardScreen`: the host owns the scaffold chrome (tab bar +
/// FAB), this view contributes the sticky "My Lists" header, the search field,
/// and the `LoadState`-driven list — all from `core-designsystem` primitives.
///
/// One-shot effects (`ShowUndoSnackbar`, `ShowError`, navigation) are drained off
/// the store: navigation runs the injected callbacks; undo/error drive a
/// bottom-anchored overlay with a 5s countdown bar. The store owns the optimistic
/// delete + undo window — this only dispatches intents and reflects state.
struct ListsDashboardView: View {
    let onOpenList: (String) -> Void
    let onCreateList: () -> Void
    let onOpenSettings: () -> Void

    private let store = InitKoinKt.resolveListsDashboardStore()
    @State private var state = ListsState(searchQuery: "", lists: LoadStateLoading(), pendingDelete: nil)
    @State private var undo: UndoSnackbarState?
    @State private var error: String?
    // Captured once per appearance so relative-time subtitles don't drift while
    // scrolling (§9). No live ticking in v1.
    @State private var now = Date()

    var body: some View {
        VStack(spacing: 0) {
            FluxItTopBarLarge(
                title: "My Lists",
                trailingIcon: FluxItTokens.Icons.settings,
                trailingAccessibilityLabel: "Settings",
                onTrailingTap: onOpenSettings
            )
            FluxItSearchField(text: searchBinding, searchIcon: FluxItTokens.Icons.search, placeholder: "Search lists")
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.top, FluxItTokens.Spacing.scaleSm)
            listsBody
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .padding(.top, FluxItTokens.Spacing.scaleMd)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(FluxItTokens.Colors.backgroundDark.ignoresSafeArea())
        .overlay(alignment: .bottom) { snackbarOverlay }
        .toolbar(.hidden, for: .navigationBar)
        .task {
            now = Date()
            await observe(store, into: $state)
        }
        .task { await observeEffects(store) { handle($0) } }
        // Mirror the store closing the window (expiry / undo / re-delete) into the
        // overlay's visibility — `pendingDelete == nil` means no live window.
        .onChange(of: state.pendingDelete == nil) { cleared in
            if cleared { undo = nil }
        }
    }

    @MainActor private func handle(_ effect: ListsEffect) {
        switch onEnum(of: effect) {
        case let .navigateToListDetail(e):
            onOpenList(e.listId())
        case .navigateToCreateList:
            onCreateList()
        case .navigateToTab:
            // Dead path in this shell — the tab bar is owned by `RootStore`, so the
            // dashboard never dispatches `TabSelected`. Handled for exhaustiveness.
            break
        case let .showUndoSnackbar(e):
            undo = UndoSnackbarState(listName: e.name, expiresAt: Date().addingTimeInterval(Double(e.secondsRemaining)))
        case let .showError(e):
            error = e.message
        }
    }

    @ViewBuilder private var listsBody: some View {
        switch onEnum(of: state.lists) {
        case .loading:
            SkeletonList()
        case .empty:
            if !state.searchQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                centeredEmpty(
                    title: "No lists matching \"\(state.searchQuery)\"",
                    icon: FluxItTokens.Icons.search,
                    message: "Try a different search."
                )
            } else {
                centeredEmpty(
                    title: "No lists yet",
                    icon: FluxItTokens.Icons.list,
                    message: "Tap + to create your first list."
                )
            }
        case let .error(err):
            VStack(spacing: FluxItTokens.Spacing.scaleLg) {
                FluxItEmptyState(title: err.message, message: "Pull to refresh, or tap below to retry.")
                FluxItPrimaryButton(label: "Retry") { store.dispatch(intent: ListsIntentRefresh()) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        case let .loaded(loaded):
            loadedList(loaded.value as? [ListSummary] ?? [])
        }
    }

    private func loadedList(_ lists: [ListSummary]) -> some View {
        List {
            // The `@JvmInline value class` id erases to an opaque `Any` in Swift
            // (no Hashable conformance), so identity keys off the stable feed
            // order; the row still dispatches intents with the boxed `summary.id`.
            ForEach(Array(lists.enumerated()), id: \.offset) { _, summary in
                FluxItDashboardListItem(
                    icon: iconImage(for: summary.icon),
                    iconTint: color(for: summary.color),
                    title: summary.name,
                    subtitle: subtitleFor(summary, now: now),
                    onTap: { store.dispatch(intent: ListsIntentOpenList(id: summary.id)) },
                    chevronIcon: FluxItTokens.Icons.chevronRight
                )
                .listRowInsets(EdgeInsets(
                    top: FluxItTokens.Spacing.scaleXs,
                    leading: FluxItTokens.Spacing.containerPadding,
                    bottom: FluxItTokens.Spacing.scaleXs,
                    trailing: FluxItTokens.Spacing.containerPadding
                ))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .fluxItSwipeToDelete { store.dispatch(intent: ListsIntentDeleteListClicked(id: summary.id)) }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { store.dispatch(intent: ListsIntentRefresh()) }
    }

    private func centeredEmpty(title: String, icon: Image, message: String) -> some View {
        FluxItEmptyState(title: title, icon: icon, message: message)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }

    @ViewBuilder private var snackbarOverlay: some View {
        if let undo {
            UndoSnackbar(state: undo, onUndo: { store.dispatch(intent: ListsIntentUndoDeleteClicked()) })
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.bottom, FluxItTokens.Spacing.scaleLg)
        } else if let error {
            ErrorSnackbar(message: error, onDismiss: { self.error = nil })
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.bottom, FluxItTokens.Spacing.scaleLg)
        }
    }

    private var searchBinding: Binding<String> {
        Binding(
            get: { state.searchQuery },
            set: { store.dispatch(intent: ListsIntentSearchQueryChanged(query: $0)) }
        )
    }
}

// MARK: - Undo / error overlays

/// A soft-deleted list's 5s undo window: the deleted [listName] and the moment
/// the window closes, from which the countdown bar derives its progress.
struct UndoSnackbarState {
    let listName: String
    let expiresAt: Date
}

private struct UndoSnackbar: View {
    let state: UndoSnackbarState
    let onUndo: () -> Void

    var body: some View {
        // TimelineView ticks the countdown bar (1 → 0) without a manual timer.
        TimelineView(.periodic(from: .now, by: 0.05)) { context in
            let remaining = max(0, state.expiresAt.timeIntervalSince(context.date))
            FluxItCard {
                VStack(spacing: FluxItTokens.Spacing.scaleSm) {
                    HStack {
                        Text("Deleted \"\(state.listName)\"")
                            .font(FluxItTokens.Typography.bodyMd.font)
                            .foregroundStyle(FluxItTokens.Colors.textPrimary)
                        Spacer()
                        Button(action: onUndo) {
                            Text("Undo")
                                .font(FluxItTokens.Typography.titleMd.font)
                                .foregroundStyle(FluxItTokens.Colors.primaryBlue)
                        }
                        .buttonStyle(.plain)
                    }
                    FluxItProgressBar(progress: remaining / UndoSnackbar.windowSeconds)
                }
            }
        }
    }

    private static let windowSeconds: Double = 5
}

private struct ErrorSnackbar: View {
    let message: String
    let onDismiss: () -> Void

    var body: some View {
        FluxItCard {
            HStack {
                Text(message)
                    .font(FluxItTokens.Typography.bodyMd.font)
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
                Spacer()
                Button(action: onDismiss) {
                    Text("Dismiss")
                        .font(FluxItTokens.Typography.titleMd.font)
                        .foregroundStyle(FluxItTokens.Colors.accentRose)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Loading placeholder (plan/07 §3): three muted rows standing in for content
/// while the first feed emission is in flight. Mirrors the Android skeleton —
/// no DS shimmer primitive yet, so it reuses the dashboard row with neutral
/// content.
private struct SkeletonList: View {
    var body: some View {
        VStack(spacing: FluxItTokens.Spacing.stackGap) {
            ForEach(0..<3, id: \.self) { _ in
                FluxItDashboardListItem(
                    icon: FluxItTokens.Icons.more,
                    iconTint: FluxItTokens.Colors.textMuted,
                    title: "Loading…",
                    subtitle: " "
                )
            }
        }
        .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

// MARK: - Row subtitle + identity mapping

/// Row subtitle per plan/07 §3 (resolved priority §12): empty list → "No items
/// yet"; otherwise "{n} items · {metadata}" where metadata is a completion
/// percent when partially done, else a relative "Last updated …". Mirrors the
/// Android `subtitleFor`. (`ListSummary` carries no explicit subtitle field, so
/// that highest-priority branch is a no-op for v1.)
func subtitleFor(_ summary: ListSummary, now: Date) -> String {
    let total = Int(summary.totalItems)
    let completed = Int(summary.completedItems)
    if total == 0 { return "No items yet" }
    let metadata: String
    if completed > 0, completed < total {
        metadata = "\(completed * 100 / total)% completed"
    } else {
        metadata = "Last updated \(relativeTime(fromEpochMillis: summary.lastActivityAt.toEpochMilliseconds(), now: now))"
    }
    return "\(total) items · \(metadata)"
}

private func relativeTime(fromEpochMillis millis: Int64, now: Date) -> String {
    let fromSeconds = Double(millis) / 1000.0
    let elapsed = max(0, now.timeIntervalSince1970 - fromSeconds)
    switch elapsed {
    case ..<60: return "just now"
    case ..<3600: return "\(Int(elapsed / 60))m ago"
    case ..<86_400: return "\(Int(elapsed / 3600))h ago"
    case ..<604_800: return "\(Int(elapsed / 86_400))d ago"
    default: return "\(Int(elapsed / 604_800))w ago"
    }
}

/// Resolves the domain's list-identity enums into DS glyphs/colors — the Swift
/// mirror of `core-designsystem`'s `FluxItIconRef.toImageVector()` /
/// `ColorToken.toColor()`. Exhaustive `switch`es so a new domain value breaks the
/// build here until a glyph/swatch is chosen.
func iconImage(for ref: FluxItIconRef) -> Image {
    switch ref {
    case .cart: return FluxItTokens.Icons.cart
    case .home: return FluxItTokens.Icons.home
    case .briefcase: return FluxItTokens.Icons.briefcase
    case .plane: return FluxItTokens.Icons.plane
    case .forkKnife: return FluxItTokens.Icons.forkKnife
    case .dumbbell: return FluxItTokens.Icons.dumbbell
    case .star: return FluxItTokens.Icons.star
    case .more: return FluxItTokens.Icons.more
    }
}

func color(for token: ColorToken) -> Color {
    switch token {
    case .primaryBlue: return FluxItTokens.Colors.primaryBlue
    case .accentRose: return FluxItTokens.Colors.accentRose
    case .accentEmerald: return FluxItTokens.Colors.accentEmerald
    case .accentOrange: return FluxItTokens.Colors.accentOrange
    case .accentIndigo: return FluxItTokens.Colors.accentIndigo
    case .accentSky: return FluxItTokens.Colors.accentSky
    }
}
