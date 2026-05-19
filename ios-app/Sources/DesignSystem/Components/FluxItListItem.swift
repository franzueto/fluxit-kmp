import SwiftUI

// Variant 1 — Dashboard list-item: 56pt tinted icon container, title+subtitle,
// optional trash + chevron trailing slots.
public struct FluxItDashboardListItem: View {
    private let icon: Image
    private let iconTint: Color
    private let title: String
    private let subtitle: String?
    private let onTap: () -> Void
    private let trashIcon: Image?
    private let onDelete: (() -> Void)?
    private let chevronIcon: Image?

    public init(
        icon: Image,
        iconTint: Color,
        title: String,
        subtitle: String? = nil,
        onTap: @escaping () -> Void = {},
        trashIcon: Image? = nil,
        onDelete: (() -> Void)? = nil,
        chevronIcon: Image? = nil
    ) {
        self.icon = icon
        self.iconTint = iconTint
        self.title = title
        self.subtitle = subtitle
        self.onTap = onTap
        self.trashIcon = trashIcon
        self.onDelete = onDelete
        self.chevronIcon = chevronIcon
    }

    public var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(iconTint.opacity(0.2))
                    icon.foregroundStyle(iconTint)
                }
                .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(FluxItTokens.Colors.textPrimary)
                    if let subtitle {
                        Text(subtitle)
                            .font(.system(size: 14))
                            .foregroundStyle(FluxItTokens.Colors.textMuted)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let trashIcon, let onDelete {
                    Button(action: onDelete) {
                        trashIcon.foregroundStyle(FluxItTokens.Colors.textMuted)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Delete")
                }
                if let chevronIcon {
                    chevronIcon.foregroundStyle(FluxItTokens.Colors.textMuted)
                }
            }
            .padding(.horizontal, FluxItTokens.Spacing.itemPaddingX)
            .padding(.vertical, FluxItTokens.Spacing.itemPaddingY)
            .background(FluxItTokens.Colors.surfaceCard)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

// Variant 2 — Detail to-buy item: hollow radio leading, chevron trailing.
public struct FluxItToBuyListItem: View {
    private let title: String
    private let onToggle: () -> Void
    private let onTap: () -> Void
    private let chevronIcon: Image?

    public init(
        title: String,
        onToggle: @escaping () -> Void,
        onTap: @escaping () -> Void = {},
        chevronIcon: Image? = nil
    ) {
        self.title = title
        self.onToggle = onToggle
        self.onTap = onTap
        self.chevronIcon = chevronIcon
    }

    public var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Button(action: onToggle) {
                    Circle()
                        .strokeBorder(FluxItTokens.Colors.textMuted, lineWidth: 2)
                        .frame(width: 24, height: 24)
                        .frame(width: 44, height: 44) // expand tap area without changing the visual
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Mark as completed")
                Text(title)
                    .font(.system(size: 16))
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let chevronIcon {
                    chevronIcon.foregroundStyle(FluxItTokens.Colors.textMuted)
                }
            }
            .padding(.horizontal, FluxItTokens.Spacing.itemPaddingX)
            .padding(.vertical, FluxItTokens.Spacing.itemPaddingY)
            .background(FluxItTokens.Colors.surfaceCardMuted)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

// Variant 3 — Detail completed item: filled check leading, strikethrough title,
// trash trailing.
public struct FluxItCompletedListItem: View {
    private let title: String
    private let onToggle: () -> Void
    private let checkIcon: Image
    private let trashIcon: Image
    private let onDelete: () -> Void

    public init(
        title: String,
        onToggle: @escaping () -> Void,
        checkIcon: Image,
        trashIcon: Image,
        onDelete: @escaping () -> Void
    ) {
        self.title = title
        self.onToggle = onToggle
        self.checkIcon = checkIcon
        self.trashIcon = trashIcon
        self.onDelete = onDelete
    }

    public var body: some View {
        HStack(spacing: 12) {
            Button(action: onToggle) {
                checkIcon
                    .foregroundStyle(FluxItTokens.Colors.primaryBlue)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Completed")
            Text(title)
                .font(.system(size: 16))
                .strikethrough()
                .foregroundStyle(FluxItTokens.Colors.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onDelete) {
                trashIcon
                    .foregroundStyle(FluxItTokens.Colors.textMuted)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Delete")
        }
        .padding(.horizontal, FluxItTokens.Spacing.itemPaddingX)
        .padding(.vertical, FluxItTokens.Spacing.itemPaddingY)
        .background(FluxItTokens.Colors.surfaceCardMuted)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}
