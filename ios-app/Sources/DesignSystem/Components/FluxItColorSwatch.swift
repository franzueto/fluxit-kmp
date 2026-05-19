import SwiftUI

public struct FluxItColorSwatch: View {
    private let color: Color
    private let selected: Bool
    private let accessibilityLabel: String?
    private let onTap: () -> Void

    public init(
        color: Color,
        selected: Bool,
        accessibilityLabel: String? = nil,
        onTap: @escaping () -> Void
    ) {
        self.color = color
        self.selected = selected
        self.accessibilityLabel = accessibilityLabel
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            ZStack {
                Circle().fill(color)
                if selected {
                    Circle().stroke(FluxItTokens.Colors.textPrimary, lineWidth: 3)
                }
            }
            .frame(width: 40, height: 40)
            .frame(width: 48, height: 48) // expand tap area without changing the visual
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel ?? "")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
