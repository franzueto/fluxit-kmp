import SwiftUI

public struct FluxItPrimaryButton: View {
    private let label: String
    private let enabled: Bool
    private let onTap: () -> Void

    public init(label: String, enabled: Bool = true, onTap: @escaping () -> Void) {
        self.label = label
        self.enabled = enabled
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            Text(label)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(FluxItTokens.Colors.textPrimary)
                .frame(maxWidth: .infinity)
                .frame(height: 56)
                .background(FluxItTokens.Colors.primaryBlue.opacity(enabled ? 1 : 0.4))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
