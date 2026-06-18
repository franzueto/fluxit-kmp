import Shared
import SwiftUI

/// The Create / Edit List modal (plan/09 §1/§2/§10), wired to `CreateListStore`.
/// The SwiftUI mirror of the Android `CreateListScreen`: a `FluxItScaffold` with a
/// centered top bar ("‹ Cancel" leading), a scrolling form (name field, icon grid,
/// color row, reminder row), and a sticky submit dock in the bottom bar — all from
/// `core-designsystem` primitives.
///
/// `editingId == nil` is **create** mode (auto-focuses the name field, submits via
/// `CreateList`, success pushes the new list's detail); a non-nil id is **edit**
/// mode (prefilled from the live list, no auto-focus, success just dismisses). The
/// store owns the §6 dirty check, so both the "‹ Cancel" button and a swipe-down
/// (blocked via `.interactiveDismissDisabled`) route through `CancelClicked`.
///
/// One-shot `CreateListEffect`s drain off the store; the `switch` is exhaustive so
/// a new effect breaks the build. `NavigateToReminderSettings` is an intentional
/// no-op in v1 (the row is disabled while `RemindersEditorEnabled` ships off).
struct CreateListView: View {
    let onDismiss: () -> Void
    let onCreated: (String) -> Void

    private let store: CreateListStore

    @State private var state: CreateListState
    @State private var confirmDiscard = false
    @State private var error: String?
    @State private var didStart = false
    @FocusState private var nameFocused: Bool

    init(editingId: String?, onDismiss: @escaping () -> Void, onCreated: @escaping (String) -> Void) {
        self.onDismiss = onDismiss
        self.onCreated = onCreated
        if let editingId {
            store = InitKoinKt.resolveCreateListStore(editingId: editingId)
        } else {
            store = InitKoinKt.resolveCreateListStore()
        }
        _state = State(initialValue: CreateListState(
            name: "",
            selectedIcon: .cart,
            selectedColor: .primaryBlue,
            reminder: nil,
            // Mirrors PaletteCatalog (full enum, declaration order). Replaced by
            // the store's real state on the first `observe` emission.
            palette: Palette(
                icons: [.cart, .home, .briefcase, .plane, .forkKnife, .dumbbell, .star, .more],
                colors: [.primaryBlue, .accentRose, .accentEmerald, .accentOrange, .accentIndigo, .accentSky]
            ),
            submission: SubmissionIdle(),
            validation: .empty,
            editing: editingId != nil,
            validationVisible: false,
            reminderEditorEnabled: false
        ))
    }

    var body: some View {
        FluxItScaffold(
            topBar: {
                FluxItTopBarCentered(
                    title: state.editing ? "Edit List" : "New List",
                    backLabel: "Cancel",
                    onBack: { store.dispatch(intent: CreateListIntentCancelClicked()) }
                )
            },
            bottomBar: { submitDock }
        ) {
            ScrollView {
                VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleXl) {
                    nameSection
                    iconGridSection
                    colorRowSection
                    reminderSection
                }
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.vertical, FluxItTokens.Spacing.scaleLg)
            }
        }
        .background(FluxItTokens.Colors.backgroundDark.ignoresSafeArea())
        .interactiveDismissDisabled(true)
        .alert("Discard changes?", isPresented: $confirmDiscard) {
            Button("Discard", role: .destructive) { store.dispatch(intent: CreateListIntentDiscardConfirmed()) }
            Button("Keep editing", role: .cancel) {}
        } message: {
            Text("Your edits to this list will be lost.")
        }
        .task {
            startIfNeeded()
            await observe(store, into: $state)
        }
        .task { await observeEffects(store) { handle($0) } }
        .onChange(of: nameFocused) { focused in
            // Only a real focus *loss* reveals validation (§4) — ignore the initial gain.
            if !focused, didStart { store.dispatch(intent: CreateListIntentNameBlurred()) }
        }
    }

    // MARK: - Lifecycle

    private func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        // §3: auto-focus only in create mode (no surprise keyboard over prefilled edits).
        if !state.editing { nameFocused = true }
    }

    // MARK: - Effect handling

    @MainActor private func handle(_ effect: CreateListEffect) {
        switch onEnum(of: effect) {
        case .dismiss:
            onDismiss()
        case .confirmDiscard:
            confirmDiscard = true
        case let .navigateToListDetail(e):
            onCreated(e.listId())
        case let .showError(e):
            error = e.message
        case .navigateToReminderSettings:
            // Unreachable in v1: the reminder row is disabled while
            // RemindersEditorEnabled ships off (plan/09 §0 decision b).
            break
        }
    }

    // MARK: - Form sections

    private var nameSection: some View {
        VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleSm) {
            FluxItTextField(
                text: Binding(
                    get: { state.name },
                    set: { store.dispatch(intent: CreateListIntentNameChanged(name: $0)) }
                ),
                label: "List name",
                placeholder: "e.g., Summer Trip",
                focused: $nameFocused
            )
            if let message = nameErrorMessage {
                Text(message)
                    .font(FluxItTokens.Typography.labelSm.font)
                    .foregroundStyle(FluxItTokens.Colors.accentRose)
            }
        }
    }

    /// §4 inline error copy — visible only after first blur or a submit attempt.
    private var nameErrorMessage: String? {
        guard state.validationVisible else { return nil }
        switch state.validation {
        case .valid: return nil
        case .empty: return "Give your list a name."
        case .tooLong: return "Keep the name under 60 characters."
        default: return nil
        }
    }

    private var iconGridSection: some View {
        VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleSm) {
            FluxItSectionHeader(label: "CHOOSE ICON")
            // §2: 4-column grid; the catalog minus the ⋯ MORE glyph (mirrors Android).
            ForEach(Array(pickableIcons.chunked(into: iconColumns).enumerated()), id: \.offset) { _, row in
                HStack(spacing: FluxItTokens.Spacing.scaleSm) {
                    ForEach(Array(row.enumerated()), id: \.offset) { _, icon in
                        let selected = icon == state.selectedIcon
                        FluxItIconChip(
                            icon: iconImage(for: icon),
                            tint: selected ? color(for: state.selectedColor) : FluxItTokens.Colors.textPrimary,
                            selected: selected,
                            accessibilityLabel: iconLabel(icon),
                            onTap: { store.dispatch(intent: CreateListIntentIconSelected(icon: icon)) }
                        )
                    }
                    Spacer(minLength: 0)
                }
            }
        }
    }

    private var colorRowSection: some View {
        VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleSm) {
            FluxItSectionHeader(label: "LIST COLOR")
            HStack(spacing: FluxItTokens.Spacing.scaleSm) {
                ForEach(Array(state.palette.colors.enumerated()), id: \.offset) { _, token in
                    FluxItColorSwatch(
                        color: color(for: token),
                        selected: token == state.selectedColor,
                        accessibilityLabel: colorLabel(token),
                        onTap: { store.dispatch(intent: CreateListIntentColorSelected(color: token)) }
                    )
                }
                Spacer(minLength: 0)
            }
        }
    }

    /// §8 Reminder Settings row — disabled in v1 ("Coming soon"); the editor lands
    /// with Phase 13 behind `RemindersEditorEnabled`, so the row never dispatches.
    private var reminderSection: some View {
        VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleSm) {
            FluxItSectionHeader(label: "REMINDER SETTINGS")
            HStack(spacing: FluxItTokens.Spacing.scaleSm) {
                FluxItTokens.Icons.bell
                    .foregroundStyle(FluxItTokens.Colors.textMuted)
                VStack(alignment: .leading) {
                    Text("Reminder Settings")
                        .font(FluxItTokens.Typography.bodyMd.font)
                        .foregroundStyle(FluxItTokens.Colors.textMuted)
                    Text(reminderSubtitle)
                        .font(FluxItTokens.Typography.labelSm.font)
                        .foregroundStyle(FluxItTokens.Colors.textMuted)
                }
                Spacer()
                FluxItTokens.Icons.chevronRight
                    .foregroundStyle(FluxItTokens.Colors.textMuted)
            }
            .padding(FluxItTokens.Spacing.scaleMd)
            .background(FluxItTokens.Colors.surfaceCard)
            .clipShape(RoundedRectangle(cornerRadius: FluxItTokens.Shapes.md, style: .continuous))
        }
    }

    private var reminderSubtitle: String {
        if !state.reminderEditorEnabled { return "Coming soon" }
        return state.reminder == nil ? "None" : "Scheduled"
    }

    // MARK: - Submit dock

    private var submitDock: some View {
        VStack(spacing: FluxItTokens.Spacing.scaleSm) {
            // §7: submission failure keeps the modal open with a banner above the button.
            if let error {
                Text(error)
                    .font(FluxItTokens.Typography.labelSm.font)
                    .foregroundStyle(FluxItTokens.Colors.accentRose)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            FluxItPrimaryButton(
                label: submitLabel,
                enabled: state.validation == .valid && !isSubmitting,
                onTap: { store.dispatch(intent: CreateListIntentCreateClicked()) }
            )
        }
        .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
        .padding(.vertical, FluxItTokens.Spacing.scaleLg)
    }

    /// §2/§7 submit copy: mode + in-flight feedback (DS button has no spinner slot).
    private var submitLabel: String {
        if isSubmitting { return state.editing ? "Saving…" : "Creating…" }
        return state.editing ? "Save" : "Create List"
    }

    private var isSubmitting: Bool {
        if case .submitting = onEnum(of: state.submission) { return true }
        return false
    }

    // MARK: - Picker helpers

    /// Icons offered (plan/09 §5): the catalog minus the `MORE` glyph (the "more"
    /// affordance is dropped in v1; `MORE` is chrome, not a list identity).
    private var pickableIcons: [FluxItIconRef] {
        state.palette.icons.filter { $0 != .more }
    }

    private let iconColumns = 4
}

/// §12: human-readable names for each chip/swatch (accessibility labels) — the
/// Swift mirror of the Android `iconLabel`/`colorLabel`. Exhaustive `switch`es so
/// a new domain value breaks the build until a name is chosen.
func iconLabel(_ icon: FluxItIconRef) -> String {
    switch icon {
    case .cart: return "Cart"
    case .home: return "Home"
    case .briefcase: return "Briefcase"
    case .plane: return "Plane"
    case .forkKnife: return "Food"
    case .dumbbell: return "Fitness"
    case .star: return "Star"
    case .more: return "More"
    }
}

func colorLabel(_ token: ColorToken) -> String {
    switch token {
    case .primaryBlue: return "Blue"
    case .accentRose: return "Rose"
    case .accentEmerald: return "Emerald"
    case .accentOrange: return "Orange"
    case .accentIndigo: return "Indigo"
    case .accentSky: return "Sky"
    }
}

private extension Array {
    /// Splits into row-chunks of `size` for the manual 4-column icon grid (the
    /// form already scrolls as a whole, so a `LazyVGrid` buys nothing here).
    func chunked(into size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0 ..< Swift.min($0 + size, count)]) }
    }
}
