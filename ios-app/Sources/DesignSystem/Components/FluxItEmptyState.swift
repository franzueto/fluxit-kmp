import SwiftUI

public struct FluxItEmptyState: View {
    private let title: String
    private let icon: Image?
    private let message: String?

    public init(title: String, icon: Image? = nil, message: String? = nil) {
        self.title = title
        self.icon = icon
        self.message = message
    }

    public var body: some View {
        VStack(spacing: 12) {
            if let icon {
                icon
                    .foregroundStyle(FluxItTokens.Colors.textMuted)
                    .frame(width: 48, height: 48)
            }
            Text(title)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(FluxItTokens.Colors.textPrimary)
                .multilineTextAlignment(.center)
            if let message {
                Text(message)
                    .font(.system(size: 16))
                    .foregroundStyle(FluxItTokens.Colors.textMuted)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }
}
