#if DEBUG
import SwiftUI

// Debug-only theme gallery. Gated by #if DEBUG so release builds don't include
// it. See plan/02_DESIGN_SYSTEM.md §9. Snapshot test (one golden per platform)
// deferred to Phase 14.

public struct ThemeGalleryView: View {
    @State private var search = ""
    @State private var composer = ""
    @State private var single = ""
    @State private var multi = ""
    @State private var selectedTab = 0

    public init() {}

    public var body: some View {
        FluxItScaffold(
            topBar: { FluxItTopBarLarge(title: "Theme Gallery") }
        ) {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    colorsSection
                    typographySection
                    shapesSection
                    spacingSection
                    primitivesSection
                    Spacer(minLength: 32)
                }
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.top, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Tokens

    private var colorsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            FluxItSectionHeader(label: "Colors")
            ForEach(Array(colorSwatches.enumerated()), id: \.offset) { _, pair in
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(pair.1)
                        .frame(width: 40, height: 40)
                    Text(pair.0)
                        .font(.system(size: 16))
                        .foregroundStyle(FluxItTokens.Colors.textPrimary)
                }
            }
        }
    }

    private var colorSwatches: [(String, Color)] {
        [
            ("backgroundDark", FluxItTokens.Colors.backgroundDark),
            ("surfaceCard", FluxItTokens.Colors.surfaceCard),
            ("surfaceCardMuted", FluxItTokens.Colors.surfaceCardMuted),
            ("surfaceSearch", FluxItTokens.Colors.surfaceSearch),
            ("textPrimary", FluxItTokens.Colors.textPrimary),
            ("textMuted", FluxItTokens.Colors.textMuted),
            ("primaryBlue", FluxItTokens.Colors.primaryBlue),
            ("primaryBlueShadow", FluxItTokens.Colors.primaryBlueShadow),
            ("dividerSubtle", FluxItTokens.Colors.dividerSubtle),
            ("accentRose", FluxItTokens.Colors.accentRose),
        ]
    }

    private var typographySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            FluxItSectionHeader(label: "Typography")
            ForEach(Array(typographySamples.enumerated()), id: \.offset) { _, sample in
                VStack(alignment: .leading) {
                    Text(sample.name)
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(FluxItTokens.Colors.textMuted)
                    Text("The quick brown fox.")
                        .font(.system(size: sample.size, weight: sample.weight))
                        .foregroundStyle(FluxItTokens.Colors.textPrimary)
                }
            }
        }
    }

    private struct TypographySample { let name: String; let size: CGFloat; let weight: Font.Weight }
    private var typographySamples: [TypographySample] {
        [
            .init(name: "displayLg", size: 32, weight: .bold),
            .init(name: "titleMd", size: 18, weight: .semibold),
            .init(name: "bodyMd", size: 16, weight: .regular),
            .init(name: "labelSm", size: 14, weight: .regular),
            .init(name: "captionXs", size: 10, weight: .medium),
        ]
    }

    private var shapesSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            FluxItSectionHeader(label: "Shapes")
            ForEach(Array(shapeSamples.enumerated()), id: \.offset) { _, pair in
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: pair.1, style: .continuous)
                        .fill(FluxItTokens.Colors.surfaceCard)
                        .frame(width: 56, height: 56)
                    Text(pair.0)
                        .font(.system(size: 16))
                        .foregroundStyle(FluxItTokens.Colors.textPrimary)
                }
            }
        }
    }

    private var shapeSamples: [(String, CGFloat)] {
        [("sm", 4), ("default", 8), ("md", 12), ("lg", 16), ("xl", 24), ("full", 9999)]
    }

    private var spacingSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            FluxItSectionHeader(label: "Spacing scale")
            ForEach(Array(spacingSamples.enumerated()), id: \.offset) { _, pair in
                HStack(spacing: 12) {
                    Rectangle()
                        .fill(FluxItTokens.Colors.primaryBlue)
                        .frame(width: pair.1, height: 8)
                    Text(pair.0)
                        .font(.system(size: 14))
                        .foregroundStyle(FluxItTokens.Colors.textMuted)
                }
            }
        }
    }

    private var spacingSamples: [(String, CGFloat)] {
        [("xs (4pt)", 4), ("sm (8pt)", 8), ("md (12pt)", 12), ("lg (16pt)", 16),
         ("xl (24pt)", 24), ("2xl (32pt)", 32), ("3xl (48pt)", 48)]
    }

    // MARK: - Primitives

    private var primitivesSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            FluxItSectionHeader(label: "Primitives")

            FluxItSearchField(text: $search, searchIcon: FluxItTokens.Icons.search)
            FluxItInlineComposer(
                text: $composer,
                onSubmit: { composer = "" },
                submitIcon: FluxItTokens.Icons.plus
            )

            FluxItTextField(text: $single, label: "Title", placeholder: "Single-line")
            FluxItTextField(text: $multi, label: "Description", placeholder: "Multi-line",
                            singleLine: false, minLines: 3)

            FluxItPrimaryButton(label: "Primary action") {}
            FluxItPrimaryButton(label: "Disabled", enabled: false) {}
            FluxItDestructiveButton(label: "Delete", trashIcon: FluxItTokens.Icons.trash) {}

            FluxItCard {
                Text("FluxItCard (default)")
                    .font(.system(size: 16))
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
            }
            FluxItCard(resting: true) {
                Text("FluxItCard (resting)")
                    .font(.system(size: 16))
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
            }

            FluxItDashboardListItem(
                icon: FluxItTokens.Icons.cart,
                iconTint: FluxItTokens.Colors.primaryBlue,
                title: "Groceries",
                subtitle: "5 items",
                trashIcon: FluxItTokens.Icons.trash,
                onDelete: {},
                chevronIcon: FluxItTokens.Icons.chevronRight
            )
            FluxItToBuyListItem(title: "Avocados", onToggle: {},
                                chevronIcon: FluxItTokens.Icons.chevronRight)
            FluxItCompletedListItem(
                title: "Bread",
                onToggle: {},
                checkIcon: FluxItTokens.Icons.check,
                trashIcon: FluxItTokens.Icons.trash,
                onDelete: {}
            )

            FluxItProgressBar(progress: 0.65)

            HStack(spacing: 12) {
                FluxItIconChip(icon: FluxItTokens.Icons.cart,
                               tint: FluxItTokens.Colors.primaryBlue, selected: true) {}
                FluxItIconChip(icon: FluxItTokens.Icons.star,
                               tint: FluxItTokens.Colors.accentRose, selected: false) {}
            }
            HStack(spacing: 8) {
                FluxItColorSwatch(color: FluxItTokens.Colors.primaryBlue, selected: true) {}
                FluxItColorSwatch(color: FluxItTokens.Colors.accentRose, selected: false) {}
                FluxItColorSwatch(color: FluxItTokens.Colors.textMuted, selected: false) {}
            }

            FluxItFab(icon: FluxItTokens.Icons.plus, accessibilityLabel: "Create") {}

            FluxItBottomTabBar(
                tabs: [
                    FluxItTabItem(icon: FluxItTokens.Icons.list, label: "Lists"),
                    FluxItTabItem(icon: FluxItTokens.Icons.calendar, label: "Calendar"),
                    FluxItTabItem(icon: FluxItTokens.Icons.bell, label: "Reminders"),
                    FluxItTabItem(icon: FluxItTokens.Icons.account, label: "Account"),
                ],
                selectedIndex: selectedTab,
                onSelect: { selectedTab = $0 }
            )

            FluxItEmptyState(
                title: "Nothing here yet",
                icon: FluxItTokens.Icons.search,
                message: "Create your first list to get started."
            )
        }
    }
}
#endif
