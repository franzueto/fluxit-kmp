import Shared
import SwiftUI

/// The sticky composer dock (plan/08 §1/§3). Binds the DS `FluxItInlineComposer`
/// to the store's `composerText`, submits via the button or the keyboard "send"
/// label, and surfaces a failed add as an inline error pill above the field
/// (the store keeps the text on failure, §14). Lives in the scaffold's bottom bar
/// so it docks above the keyboard.
struct ComposerDock: View {
    let text: String
    let composerError: String?
    let onTextChange: (String) -> Void
    let onSubmit: () -> Void

    var body: some View {
        VStack(spacing: FluxItTokens.Spacing.scaleSm) {
            if let composerError {
                Text(composerError)
                    .font(.system(size: 13))
                    .foregroundStyle(FluxItTokens.Colors.accentRose)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            FluxItInlineComposer(
                text: textBinding,
                onSubmit: onSubmit,
                submitIcon: FluxItTokens.Icons.plus,
                placeholder: "Add new item…"
            )
            .submitLabel(.send)
        }
        .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
        .padding(.vertical, FluxItTokens.Spacing.scaleSm)
        .background(FluxItTokens.Colors.backgroundDark)
    }

    private var textBinding: Binding<String> {
        Binding(get: { text }, set: { onTextChange($0) })
    }
}
