import SwiftUI

/// The list-actions menu (plan/08 §4), presented as a `.confirmationDialog`.
/// v1 wires **Edit list details** (Phase 09 — opens the Create/Edit modal) and
/// **Clear completed** (with a destructive confirmation step, §13). The Star /
/// Reminders / Delete-list entries depend on stores/use cases that land in later
/// phases and the shipped `ListDetailStore` exposes no intents for them, so they
/// are omitted here rather than shown disabled (a confirmationDialog can't render
/// disabled rows) — documented divergence from §4, matching the Android sheet's
/// "(coming soon)" rows.
struct ListActionsSheet: ViewModifier {
    @Binding var isPresented: Bool
    let onEditList: () -> Void
    let onClearCompleted: () -> Void

    @State private var confirmClear = false

    func body(content: Content) -> some View {
        content
            .confirmationDialog("List actions", isPresented: $isPresented, titleVisibility: .visible) {
                Button("Edit list details", action: onEditList)
                Button("Clear completed", role: .destructive) { confirmClear = true }
                Button("Cancel", role: .cancel) {}
            }
            .confirmationDialog(
                "Clear completed?",
                isPresented: $confirmClear,
                titleVisibility: .visible
            ) {
                Button("Clear", role: .destructive, action: onClearCompleted)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Completed items will be removed from this list.")
            }
    }
}

extension View {
    /// Attaches the list-actions menu, opened by binding `isPresented` to the
    /// store's `OpenListMenu` effect.
    func listActionsSheet(
        isPresented: Binding<Bool>,
        onEditList: @escaping () -> Void,
        onClearCompleted: @escaping () -> Void
    ) -> some View {
        modifier(ListActionsSheet(isPresented: isPresented, onEditList: onEditList, onClearCompleted: onClearCompleted))
    }
}
