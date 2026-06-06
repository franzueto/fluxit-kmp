import Shared
import SwiftUI

/// The List Detail screen (plan/08 §1/§3), wired to `ListDetailStore`. Mirrors the
/// Android `ListDetailScreen`: a `FluxItScaffold` with the variant-B top bar, the
/// completion header + progress bar above a scrolling `List` of TO BUY / COMPLETED
/// sections, and a sticky `ComposerDock` in the bottom bar. All from
/// `core-designsystem` primitives.
///
/// One-shot effects (`NavigateBack`, `NavigateToEditItem`, `OpenListMenu`,
/// `ShowUndoSnackbar`, `ShowError`) drain off the store; the `switch` is exhaustive
/// so a new effect breaks the build. §5 process-death restore uses `@SceneStorage`
/// (keyed per list), replayed into the store as intents on first appearance.
struct ListDetailView: View {
    let onBack: () -> Void
    let onOpenEditItem: (String) -> Void

    private let listId: String
    private let store = InitKoinKt.resolveListDetailStore()

    @State private var state = ListDetailState(
        header: LoadStateLoading(),
        sections: LoadStateLoading(),
        composerText: "",
        composerError: nil,
        showCompleted: true,
        pendingDelete: nil
    )
    @State private var undo: UndoSnackbarState?
    @State private var error: String?
    @State private var showMenu = false
    @State private var didStart = false

    @SceneStorage private var composerScene: String
    @SceneStorage private var showCompletedScene: Bool

    init(listId: String, onBack: @escaping () -> Void, onOpenEditItem: @escaping (String) -> Void) {
        self.listId = listId
        self.onBack = onBack
        self.onOpenEditItem = onOpenEditItem
        _composerScene = SceneStorage(wrappedValue: "", "composer:\(listId)")
        _showCompletedScene = SceneStorage(wrappedValue: true, "showCompleted:\(listId)")
    }

    var body: some View {
        FluxItScaffold {
            FluxItTopBarCentered(
                title: headerTitle,
                backLabel: "Lists",
                onBack: { store.dispatch(intent: ListDetailIntentBackClicked()) },
                trailingIcon: FluxItTokens.Icons.more,
                trailingAccessibilityLabel: "List actions",
                onTrailingTap: { store.dispatch(intent: ListDetailIntentMoreClicked()) }
            )
        } bottomBar: {
            if isHeaderLoaded {
                ComposerDock(
                    text: state.composerText,
                    composerError: state.composerError,
                    onTextChange: { store.dispatch(intent: ListDetailIntentComposerTextChanged(text: $0)) },
                    onSubmit: { store.dispatch(intent: ListDetailIntentComposerSubmit()) }
                )
            }
        } content: {
            detailContent
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .background(FluxItTokens.Colors.backgroundDark.ignoresSafeArea())
        .overlay(alignment: .bottom) { snackbarOverlay }
        .toolbar(.hidden, for: .navigationBar)
        .listActionsSheet(
            isPresented: $showMenu,
            onClearCompleted: { store.dispatch(intent: ListDetailIntentClearCompletedClicked()) }
        )
        .task {
            startIfNeeded()
            await observe(store, into: $state)
        }
        .task { await observeEffects(store) { handle($0) } }
        .onChange(of: state.pendingDelete == nil) { cleared in
            if cleared { undo = nil }
        }
        .onChange(of: state.composerText) { composerScene = $0 }
        .onChange(of: state.showCompleted) { showCompletedScene = $0 }
    }

    // MARK: - Lifecycle / persistence (§5)

    private func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        store.dispatch(intent: ListDetailIntentInit(listId: IosEffectIdsKt.listIdOf(value: listId)))
        // Replay the persisted composer text + hide preference as intents — the
        // store has no `initialState` ctor param (a §5 sketch that never landed).
        if !composerScene.isEmpty {
            store.dispatch(intent: ListDetailIntentComposerTextChanged(text: composerScene))
        }
        if !showCompletedScene {
            store.dispatch(intent: ListDetailIntentToggleShowCompleted())
        }
    }

    // MARK: - Effect handling

    @MainActor private func handle(_ effect: ListDetailEffect) {
        switch onEnum(of: effect) {
        case .navigateBack:
            onBack()
        case let .navigateToEditItem(e):
            onOpenEditItem(e.itemId())
        case .openListMenu:
            showMenu = true
        case let .showUndoSnackbar(e):
            undo = UndoSnackbarState(
                listName: e.title,
                expiresAt: Date().addingTimeInterval(Double(e.secondsRemaining))
            )
        case let .showError(e):
            error = e.message
        }
    }

    // MARK: - Header / content

    private var headerTitle: String {
        if case let .loaded(loaded) = onEnum(of: state.header), let detail = loaded.value as? ListDetail {
            return detail.name
        }
        return ""
    }

    private var isHeaderLoaded: Bool {
        if case .loaded = onEnum(of: state.header) { return true }
        return false
    }

    @ViewBuilder private var detailContent: some View {
        switch onEnum(of: state.header) {
        case .loading:
            centeredSpinner
        case let .error(err):
            FluxItEmptyState(title: err.message)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        case .empty, .loaded:
            sectionsContent
        }
    }

    @ViewBuilder private var sectionsContent: some View {
        switch onEnum(of: state.sections) {
        case .loading:
            centeredSpinner
        case .empty:
            FluxItEmptyState(title: "No items yet", message: "Tap the field below to add your first item.")
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        case let .error(err):
            FluxItEmptyState(title: err.message)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
        case let .loaded(loaded):
            if let section = loaded.value as? ItemsSection {
                loadedSections(section)
            }
        }
    }

    private func loadedSections(_ section: ItemsSection) -> some View {
        VStack(spacing: 0) {
            CompletionHeaderView(section: section)
            List {
                if !section.active.isEmpty {
                    headerRow(FluxItSectionHeader(label: "TO BUY"))
                    ForEach(Array(section.active.enumerated()), id: \.offset) { _, item in
                        itemRow(item)
                    }
                }
                if !section.completed.isEmpty {
                    headerRow(FluxItSectionHeader(
                        label: "COMPLETED",
                        trailingActionLabel: state.showCompleted ? "Hide" : "Show",
                        onTrailingAction: { store.dispatch(intent: ListDetailIntentToggleShowCompleted()) }
                    ))
                    if state.showCompleted {
                        ForEach(Array(section.completed.enumerated()), id: \.offset) { _, item in
                            itemRow(item)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func itemRow(_ item: Item) -> some View {
        ListDetailRow(
            item: item,
            onToggle: { store.dispatch(intent: ListDetailIntentItemCompletionToggled(id: item.id)) },
            onTap: { store.dispatch(intent: ListDetailIntentItemTapped(id: item.id)) }
        )
        .plainRow()
        .fluxItSwipeToDelete { store.dispatch(intent: ListDetailIntentItemDeleteClicked(id: item.id)) }
    }

    private func headerRow(_ header: some View) -> some View {
        header.plainRow()
    }

    private var centeredSpinner: some View {
        ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }

    @ViewBuilder private var snackbarOverlay: some View {
        if let undo {
            DetailUndoSnackbar(state: undo, onUndo: { store.dispatch(intent: ListDetailIntentUndoItemDeleteClicked()) })
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.bottom, FluxItTokens.Spacing.scaleLg)
        } else if let error {
            DetailErrorSnackbar(message: error, onDismiss: { self.error = nil })
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.bottom, FluxItTokens.Spacing.scaleLg)
        }
    }
}

// MARK: - Completion header

/// Completion header (plan/08 §1): "LIST COMPLETION" caption + "{completed}/{total}"
/// + a full-width progress bar. Lives outside the scrolling `List` so a single
/// row's completion flip doesn't recompose the rows (§7).
private struct CompletionHeaderView: View {
    let section: ItemsSection

    private var fraction: Double {
        section.total == 0 ? 0 : Double(section.completedCount) / Double(section.total)
    }

    var body: some View {
        VStack(spacing: FluxItTokens.Spacing.scaleSm) {
            HStack {
                Text("LIST COMPLETION")
                    .font(FluxItTokens.Typography.captionXs.font)
                    .foregroundStyle(FluxItTokens.Colors.textMuted)
                Spacer()
                Text("\(section.completedCount)/\(section.total)")
                    .font(FluxItTokens.Typography.bodyMd.font)
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
            }
            FluxItProgressBar(progress: fraction)
        }
        .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
        .padding(.vertical, FluxItTokens.Spacing.scaleSm)
    }
}

// MARK: - Undo / error overlays (local twins of the dashboard's, §6)

private struct DetailUndoSnackbar: View {
    let state: UndoSnackbarState
    let onUndo: () -> Void

    var body: some View {
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
                    FluxItProgressBar(progress: remaining / 5)
                }
            }
        }
    }
}

private struct DetailErrorSnackbar: View {
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

private extension View {
    /// Strips the default `List` row chrome so DS rows render edge-to-edge on the
    /// dark background — mirrors the dashboard's row insets.
    func plainRow() -> some View {
        listRowInsets(EdgeInsets(
            top: FluxItTokens.Spacing.scaleXs,
            leading: FluxItTokens.Spacing.containerPadding,
            bottom: FluxItTokens.Spacing.scaleXs,
            trailing: FluxItTokens.Spacing.containerPadding
        ))
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}
