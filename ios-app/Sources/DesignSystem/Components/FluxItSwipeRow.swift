import SwiftUI

// iOS counterpart of the Android `FluxItSwipeRow` primitive (Phase 02 §5,
// backfilled in Phase 07 Slice 2; the iOS half lands with the dashboard in
// Slice 7). Where Compose wraps a row in a `SwipeToDismissBox`, SwiftUI's
// reveal-on-swipe lives on `List` rows via `.swipeActions`, so the DS exposes it
// as a `View` modifier rather than a wrapper view. The destructive button is
// rose-tinted to match the Android delete affordance; the store owns the
// optimistic removal + 5s undo, so this only emits `onDelete`.
public extension View {
    func fluxItSwipeToDelete(
        deleteIcon: Image = FluxItTokens.Icons.trash,
        onDelete: @escaping () -> Void
    ) -> some View {
        swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive, action: onDelete) {
                Label { Text("Delete") } icon: { deleteIcon }
            }
            .tint(FluxItTokens.Colors.accentRose)
        }
    }
}
