import SwiftUI

public struct FluxItSectionHeader: View {
    private let label: String
    private let trailingActionLabel: String?
    private let onTrailingAction: () -> Void

    public init(
        label: String,
        trailingActionLabel: String? = nil,
        onTrailingAction: @escaping () -> Void = {}
    ) {
        self.label = label
        self.trailingActionLabel = trailingActionLabel
        self.onTrailingAction = onTrailingAction
    }

    public var body: some View {
        HStack {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(FluxItTokens.Colors.textMuted)
            Spacer()
            if let trailingActionLabel {
                Button(trailingActionLabel, action: onTrailingAction)
                    .font(.system(size: 14))
                    .foregroundStyle(FluxItTokens.Colors.primaryBlue)
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
    }
}
