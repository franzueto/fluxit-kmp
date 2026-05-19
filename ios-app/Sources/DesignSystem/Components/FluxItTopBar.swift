import SwiftUI

// Variant A — large display.lg title + optional trailing icon button. Lists Dashboard.
public struct FluxItTopBarLarge: View {
    private let title: String
    private let trailingIcon: Image?
    private let trailingAccessibilityLabel: String?
    private let onTrailingTap: () -> Void

    public init(
        title: String,
        trailingIcon: Image? = nil,
        trailingAccessibilityLabel: String? = nil,
        onTrailingTap: @escaping () -> Void = {}
    ) {
        self.title = title
        self.trailingIcon = trailingIcon
        self.trailingAccessibilityLabel = trailingAccessibilityLabel
        self.onTrailingTap = onTrailingTap
    }

    public var body: some View {
        HStack(alignment: .center) {
            Text(title)
                .font(.system(size: 32, weight: .bold))
                .tracking(-0.64) // -0.02em of 32px
                .foregroundStyle(FluxItTokens.Colors.textPrimary)
            Spacer()
            if let trailingIcon {
                Button(action: onTrailingTap) {
                    trailingIcon
                        .foregroundStyle(FluxItTokens.Colors.textPrimary)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel(trailingAccessibilityLabel ?? "")
            }
        }
        .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(FluxItBarBackground())
    }
}

// Variant B — centered title, leading back text-button, optional trailing icon. List Detail / Edit Item.
public struct FluxItTopBarCentered: View {
    private let title: String
    private let backLabel: String
    private let onBack: () -> Void
    private let trailingIcon: Image?
    private let trailingAccessibilityLabel: String?
    private let onTrailingTap: () -> Void

    public init(
        title: String,
        backLabel: String,
        onBack: @escaping () -> Void,
        trailingIcon: Image? = nil,
        trailingAccessibilityLabel: String? = nil,
        onTrailingTap: @escaping () -> Void = {}
    ) {
        self.title = title
        self.backLabel = backLabel
        self.onBack = onBack
        self.trailingIcon = trailingIcon
        self.trailingAccessibilityLabel = trailingAccessibilityLabel
        self.onTrailingTap = onTrailingTap
    }

    public var body: some View {
        ZStack {
            Text(title)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(FluxItTokens.Colors.textPrimary)
            HStack {
                Button(action: onBack) {
                    Text("‹ \(backLabel)")
                        .font(.system(size: 16))
                        .foregroundStyle(FluxItTokens.Colors.primaryBlue)
                }
                Spacer()
                if let trailingIcon {
                    Button(action: onTrailingTap) {
                        trailingIcon
                            .foregroundStyle(FluxItTokens.Colors.textPrimary)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(trailingAccessibilityLabel ?? "")
                } else {
                    Color.clear.frame(width: 44, height: 44) // symmetric spacer
                }
            }
        }
        .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
        .frame(height: 48)
        .frame(maxWidth: .infinity)
        .background(FluxItBarBackground())
    }
}

// Shared backdrop for top + bottom bars. iOS gets native blur via
// .ultraThinMaterial; §7 may layer additional treatment.
struct FluxItBarBackground: View {
    var body: some View {
        Rectangle()
            .fill(.ultraThinMaterial)
            .overlay(FluxItTokens.Colors.surfaceCard.opacity(0.4))
            .ignoresSafeArea(edges: .top)
    }
}
