import Shared
import SwiftUI

/// A single list-detail row (plan/08 §2), wrapping the DS active/completed
/// variants. Active rows carry the optional subtitle + chevron; completed rows
/// strike through and omit the trash (swipe-to-delete handles it, §2). Both
/// dispatch the toggle / tap / delete intents straight to the store.
struct ListDetailRow: View {
    let item: Item
    let onToggle: () -> Void
    let onTap: () -> Void

    var body: some View {
        if item.isCompleted {
            FluxItCompletedListItem(
                title: item.title,
                onToggle: onToggle,
                checkIcon: FluxItTokens.Icons.check,
                onTap: onTap
            )
        } else {
            FluxItToBuyListItem(
                title: item.title,
                onToggle: onToggle,
                subtitle: item.subtitle,
                onTap: onTap,
                chevronIcon: FluxItTokens.Icons.chevronRight
            )
        }
    }
}
