import SwiftUI

public struct FluxItIconChip: View {
    private let icon: Image
    private let tint: Color
    private let selected: Bool
    private let accessibilityLabel: String?
    private let onTap: () -> Void

    public init(
        icon: Image,
        tint: Color,
        selected: Bool,
        accessibilityLabel: String? = nil,
        onTap: @escaping () -> Void
    ) {
        self.icon = icon
        self.tint = tint
        self.selected = selected
        self.accessibilityLabel = accessibilityLabel
        self.onTap = onTap
    }

    public var body: some View {
        let resolvedTint = selected ? FluxItTokens.Colors.primaryBlue : tint
        Button(action: onTap) {
            ZStack {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(resolvedTint.opacity(0.2))
                if selected {
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .stroke(FluxItTokens.Colors.primaryBlue, lineWidth: 2)
                }
                icon.foregroundStyle(resolvedTint)
            }
            .frame(width: 80, height: 80)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel ?? "")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
