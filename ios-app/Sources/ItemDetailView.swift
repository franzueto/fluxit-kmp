import Shared
import SwiftUI

/// The Edit-Item screen (plan/10 §1), wired to `ItemDetailStore`. The SwiftUI
/// mirror of the Android `ItemDetailScreen`: a `FluxItScaffold` with a centered top
/// bar ("‹ Back" leading), a scrolling form (general info, photo, optional
/// permission banner, delete, last-edited footer), and a sticky Save dock in the
/// bottom bar — all from `core-designsystem` primitives.
///
/// **§1 divergence (shared with Android):** Save lives in the bottom dock, not a
/// top-bar text trailing — the DS centered top bar exposes only an icon trailing
/// with no disabled state. The store owns the §5 dirty check, so the "‹ Back"
/// button routes through `BackClicked` (→ `ConfirmDiscardChanges` when dirty).
///
/// Photo capture is fully handled by the store + `AttachPhotoToItem` over the Phase
/// 06 `IosPhotoCapture` (`UIImagePickerController`); this screen just dispatches
/// `PhotoSourceSelected` and renders `PhotoStatus`. A `Loaded` photo is decoded
/// from its absolute path via `UIImage(contentsOfFile:)` — no Coil/Kingfisher,
/// matching the Android v1 simple-decode divergence.
///
/// One-shot `ItemDetailEffect`s drain off the store; the `switch` is exhaustive so
/// a new effect breaks the build. The §4 permission banner is iOS-camera-only (the
/// only place `Request*` effects actually fire — Android's system camera owns its
/// own permission).
///
/// **Divergence from plan/10 §8:** the iOS screen ships as this single file rather
/// than the listed `ItemDetailView` / `PhotoSection` / `PermissionBanner` /
/// `ItemDetailFormSections` / `ItemDetailPreviews` split — mirroring the Phase 09
/// `CreateListView.swift` monolith (Swift `private` is file-scoped, so the Android
/// internal-helper split buys nothing here; SwiftUI previews are dropped on iOS).
struct ItemDetailView: View {
    let itemId: String
    let onBack: () -> Void

    // Held in @State, not a plain `let`: SwiftUI re-creates this view struct every
    // time the owning `listsPath` mutates (each push/pop re-runs ContentView.body),
    // and a `let store = resolve()` would mint a fresh Koin factory store on each
    // re-creation — stranding the `.task` observers on the original instance while
    // button taps dispatch to the new one (frozen spinner + dead back button).
    // @State evaluates its initializer once per view identity and preserves it.
    @State private var store = InitKoinKt.resolveItemDetailStore()

    @State private var state = ItemDetailState(
        item: LoadStateLoading(),
        editing: ItemPatch(title: "", subtitle: nil, description: nil, photoId: nil),
        dirty: false,
        photoStatus: PhotoStatusNone(),
        showPhotoSourceSheet: false,
        confirmDelete: false,
        submitting: false,
        titleValidation: .empty
    )
    @State private var confirmDiscard = false
    @State private var error: String?
    /// §4 in-section permission affordance — iOS-camera-only. Cleared on the next
    /// photo re-attempt (`UpdatePhotoClicked`).
    @State private var permissionBanner: PermissionTarget?
    @State private var didStart = false

    var body: some View {
        FluxItScaffold(
            topBar: {
                FluxItTopBarCentered(
                    title: "Edit Item",
                    backLabel: "Back",
                    // System/UI back routes through the store's dirty check (§5).
                    onBack: { store.dispatch(intent: ItemDetailIntentBackClicked()) }
                )
            },
            bottomBar: { saveDock }
        ) {
            ScrollView {
                content
                    .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                    .padding(.vertical, FluxItTokens.Spacing.scaleLg)
            }
        }
        .background(FluxItTokens.Colors.backgroundDark.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .alert("Delete this item?", isPresented: confirmDeleteBinding) {
            Button("Delete", role: .destructive) { store.dispatch(intent: ItemDetailIntentConfirmDelete()) }
            Button("Cancel", role: .cancel) { store.dispatch(intent: ItemDetailIntentCancelDelete()) }
        } message: {
            Text("This can't be undone.")
        }
        .alert("Discard changes?", isPresented: $confirmDiscard) {
            // The store only emits ConfirmDiscardChanges (no DiscardConfirmed intent) —
            // the host owns the choice, so Discard just pops (§5).
            Button("Discard", role: .destructive) { onBack() }
            Button("Keep editing", role: .cancel) {}
        } message: {
            Text("Your edits to this item will be lost.")
        }
        .confirmationDialog("Item Photo", isPresented: photoSheetBinding, titleVisibility: .hidden) {
            Button("Take Photo") { store.dispatch(intent: ItemDetailIntentPhotoSourceSelected(source: .camera)) }
            Button("Choose from Library") { store.dispatch(intent: ItemDetailIntentPhotoSourceSelected(source: .library)) }
            // §15: "Remove Photo" appears only when a photo is attached.
            if isPhotoLoaded {
                Button("Remove Photo", role: .destructive) { store.dispatch(intent: ItemDetailIntentRemovePhotoClicked()) }
            }
        }
        .task {
            startIfNeeded()
            await observe(store, into: $state)
        }
        .task { await observeEffects(store) { handle($0) } }
    }

    // MARK: - Lifecycle

    private func startIfNeeded() {
        guard !didStart else { return }
        didStart = true
        store.dispatch(intent: ItemDetailIntentInit(itemId: IosEffectIdsKt.itemIdOf(value: itemId)))
    }

    // MARK: - Effect handling

    @MainActor private func handle(_ effect: ItemDetailEffect) {
        switch onEnum(of: effect) {
        case .navigateBack:
            onBack()
        case .confirmDiscardChanges:
            confirmDiscard = true
        case .requestCameraPermission:
            permissionBanner = .camera
        case .requestPhotoLibraryAccess:
            permissionBanner = .library
        case let .showError(e):
            error = e.message
        }
    }

    // MARK: - Content

    @ViewBuilder private var content: some View {
        switch onEnum(of: state.item) {
        case let .loaded(loaded):
            if let item = loaded.value as? Item {
                formBody(item)
            }
        case let .error(e):
            CenteredMessage(message: e.message)
        case .loading, .empty:
            ProgressView()
                .tint(FluxItTokens.Colors.primaryBlue)
                .frame(maxWidth: .infinity, minHeight: 240)
        }
    }

    private func formBody(_ item: Item) -> some View {
        VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleXl) {
            generalInfoSection
            photoSection
            if let permissionBanner {
                PermissionBanner(target: permissionBanner, onOpenSettings: openSystemSettings)
            }
            deleteSection
            lastEditedFooter(item.updatedAt.toEpochMilliseconds())
        }
    }

    // MARK: - General info (§1)

    private var generalInfoSection: some View {
        VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleMd) {
            FluxItSectionHeader(label: "GENERAL INFO")
            VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleSm) {
                FluxItTextField(
                    text: Binding(
                        get: { state.editing.title },
                        set: { store.dispatch(intent: ItemDetailIntentTitleChanged(title: $0)) }
                    ),
                    label: "Item name",
                    placeholder: "e.g., Olive oil"
                )
                if let message = titleErrorMessage {
                    Text(message)
                        .font(FluxItTokens.Typography.labelSm.font)
                        .foregroundStyle(FluxItTokens.Colors.accentRose)
                }
            }
            FluxItTextField(
                text: Binding(
                    get: { state.editing.description ?? "" },
                    set: { store.dispatch(intent: ItemDetailIntentDescriptionChanged(description: $0.isEmpty ? nil : $0)) }
                ),
                label: "Description",
                placeholder: "Add notes, brand, quantity…",
                singleLine: false,
                minLines: 4
            )
        }
    }

    /// §2 inline title-error copy. No `validationVisible` gate (the field is prefilled
    /// valid), so an error shows whenever the live title is invalid.
    private var titleErrorMessage: String? {
        switch state.titleValidation {
        case .valid: return nil
        case .empty: return "Give this item a name."
        case .tooLong: return "Keep the name under 120 characters."
        default: return nil
        }
    }

    // MARK: - Photo (§1/§14)

    private var photoSection: some View {
        VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleSm) {
            FluxItSectionHeader(
                label: "ITEM PHOTO",
                trailingActionLabel: "Update",
                onTrailingAction: { updatePhoto() }
            )
            // The card itself is tappable (§13 divergence — more discoverable on touch).
            Button(action: { updatePhoto() }) { photoCard }
                .buttonStyle(.plain)
        }
    }

    @ViewBuilder private var photoCard: some View {
        FluxItCard {
            ZStack {
                switch onEnum(of: state.photoStatus) {
                case let .loaded(loaded):
                    loadedPhoto(uri: loaded.uri)
                case .none:
                    emptyPhoto
                case .capturing, .uploading:
                    ProgressView().tint(FluxItTokens.Colors.primaryBlue)
                case .error:
                    errorPhoto
                }
            }
            .frame(maxWidth: .infinity)
            .aspectRatio(16.0 / 9.0, contentMode: .fit)
        }
    }

    @ViewBuilder private func loadedPhoto(uri: String) -> some View {
        // §13: simple-decode from the absolute path, no Coil/Kingfisher (Android parity).
        if let image = UIImage(contentsOfFile: uri) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
        } else {
            ProgressView().tint(FluxItTokens.Colors.primaryBlue)
        }
    }

    private var emptyPhoto: some View {
        VStack(spacing: FluxItTokens.Spacing.scaleSm) {
            FluxItTokens.Icons.camera.foregroundStyle(FluxItTokens.Colors.textMuted)
            Text("No photo yet")
                .font(FluxItTokens.Typography.bodyMd.font)
                .foregroundStyle(FluxItTokens.Colors.textPrimary)
            Text("Tap to add one")
                .font(FluxItTokens.Typography.labelSm.font)
                .foregroundStyle(FluxItTokens.Colors.textMuted)
        }
    }

    private var errorPhoto: some View {
        VStack(spacing: FluxItTokens.Spacing.scaleSm) {
            Text("Couldn't add that photo.")
                .font(FluxItTokens.Typography.bodyMd.font)
                .foregroundStyle(FluxItTokens.Colors.textPrimary)
            Text("Tap to try again")
                .font(FluxItTokens.Typography.labelSm.font)
                .foregroundStyle(FluxItTokens.Colors.textMuted)
        }
    }

    /// Clear a stale permission banner once the user re-attempts a photo (§4).
    private func updatePhoto() {
        permissionBanner = nil
        store.dispatch(intent: ItemDetailIntentUpdatePhotoClicked())
    }

    private var isPhotoLoaded: Bool {
        if case .loaded = onEnum(of: state.photoStatus) { return true }
        return false
    }

    // MARK: - Delete + footer (§1)

    private var deleteSection: some View {
        FluxItDestructiveButton(
            label: "Delete Item",
            trashIcon: FluxItTokens.Icons.trash,
            onTap: { store.dispatch(intent: ItemDetailIntentDeleteClicked()) }
        )
    }

    private func lastEditedFooter(_ updatedAtMillis: Int64) -> some View {
        Text(lastEditedLabel(updatedAtMillis))
            .font(FluxItTokens.Typography.captionXs.font)
            .foregroundStyle(FluxItTokens.Colors.textMuted)
            .frame(maxWidth: .infinity, alignment: .center)
    }

    // MARK: - Save dock (§5)

    private var saveDock: some View {
        VStack(spacing: FluxItTokens.Spacing.scaleSm) {
            // §5: submission failure keeps the screen open with a banner above the button.
            if let error {
                Text(error)
                    .font(FluxItTokens.Typography.labelSm.font)
                    .foregroundStyle(FluxItTokens.Colors.accentRose)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            FluxItPrimaryButton(
                label: state.submitting ? "Saving…" : "Save",
                enabled: state.dirty && state.titleValidation == .valid && !state.submitting,
                onTap: { store.dispatch(intent: ItemDetailIntentSaveClicked()) }
            )
        }
        .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
        .padding(.vertical, FluxItTokens.Spacing.scaleLg)
    }

    // MARK: - Bindings / helpers

    private var confirmDeleteBinding: Binding<Bool> {
        Binding(
            get: { state.confirmDelete },
            set: { if !$0 { store.dispatch(intent: ItemDetailIntentCancelDelete()) } }
        )
    }

    private var photoSheetBinding: Binding<Bool> {
        Binding(
            get: { state.showPhotoSourceSheet },
            set: { if !$0 { store.dispatch(intent: ItemDetailIntentPhotoSourceSheetDismissed()) } }
        )
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

/// §1 footer copy, e.g. "Last edited on Jun 18, 2026" (local time zone). The Swift
/// mirror of the Android `lastEditedLabel`.
private func lastEditedLabel(_ updatedAtMillis: Int64) -> String {
    let date = Date(timeIntervalSince1970: Double(updatedAtMillis) / 1000)
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US")
    formatter.dateFormat = "MMM d, yyyy"
    return "Last edited on \(formatter.string(from: date))"
}

/// Which access the in-photo permission banner is asking for (§4). Drives the copy
/// only; the recovery action is the same "Open Settings" deep link for both.
///
/// **Divergence (plan/10 §0 b):** no soft-vs-hard split — the domain surfaces a flat
/// `PermissionDenied`. This is where the banner actually fires (iOS-camera-only).
enum PermissionTarget {
    case camera
    case library
}

/// §4 in-section permission affordance: contextual card with an Open Settings CTA.
private struct PermissionBanner: View {
    let target: PermissionTarget
    let onOpenSettings: () -> Void

    private var copy: String {
        switch target {
        case .camera: return "Camera access is off. Enable it in Settings to take photos."
        case .library: return "Photo access is off. Enable it in Settings to choose a photo."
        }
    }

    var body: some View {
        FluxItCard {
            VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleSm) {
                Text(copy)
                    .font(FluxItTokens.Typography.bodyMd.font)
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
                Button("Open Settings", action: onOpenSettings)
                    .font(FluxItTokens.Typography.titleMd.font)
                    .foregroundStyle(FluxItTokens.Colors.primaryBlue)
                    .buttonStyle(.plain)
            }
        }
    }
}

/// §1 centered load-error / fallback message.
private struct CenteredMessage: View {
    let message: String

    var body: some View {
        Text(message)
            .font(FluxItTokens.Typography.bodyMd.font)
            .foregroundStyle(FluxItTokens.Colors.textMuted)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 240)
    }
}
