import SwiftUI

public struct FluxItFab: View {
    private let icon: Image
    private let accessibilityLabel: String?
    private let onTap: () -> Void

    public init(icon: Image, accessibilityLabel: String? = nil, onTap: @escaping () -> Void) {
        self.icon = icon
        self.accessibilityLabel = accessibilityLabel
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            icon
                .foregroundStyle(FluxItTokens.Colors.textPrimary)
                .frame(width: 64, height: 64)
                .background(FluxItTokens.Colors.primaryBlue)
                .clipShape(Circle())
                .shadow(color: FluxItTokens.Colors.primaryBlueShadow, radius: 16, x: 0, y: 8)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel ?? "")
    }
}
