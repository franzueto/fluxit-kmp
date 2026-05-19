import SwiftUI

public struct FluxItDestructiveButton: View {
    private let label: String
    private let trashIcon: Image
    private let onTap: () -> Void

    public init(label: String, trashIcon: Image, onTap: @escaping () -> Void) {
        self.label = label
        self.trashIcon = trashIcon
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                trashIcon.foregroundStyle(FluxItTokens.Colors.accentRose)
                Text(label)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(FluxItTokens.Colors.accentRose)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(FluxItTokens.Colors.accentRose, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}
