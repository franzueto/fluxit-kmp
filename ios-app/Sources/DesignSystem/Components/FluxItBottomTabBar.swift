import SwiftUI

public struct FluxItTabItem {
    public let icon: Image
    public let activeIcon: Image
    public let label: String

    public init(icon: Image, activeIcon: Image? = nil, label: String) {
        self.icon = icon
        self.activeIcon = activeIcon ?? icon
        self.label = label
    }
}

public struct FluxItBottomTabBar: View {
    private let tabs: [FluxItTabItem]
    private let selectedIndex: Int
    private let onSelect: (Int) -> Void

    public init(tabs: [FluxItTabItem], selectedIndex: Int, onSelect: @escaping (Int) -> Void) {
        self.tabs = tabs
        self.selectedIndex = selectedIndex
        self.onSelect = onSelect
    }

    public var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(tabs.enumerated()), id: \.offset) { index, tab in
                let active = index == selectedIndex
                Button(action: { onSelect(index) }) {
                    VStack(spacing: 4) {
                        (active ? tab.activeIcon : tab.icon)
                            .foregroundStyle(active
                                ? FluxItTokens.Colors.primaryBlue
                                : FluxItTokens.Colors.textMuted)
                        Text(tab.label)
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(active
                                ? FluxItTokens.Colors.primaryBlue
                                : FluxItTokens.Colors.textMuted)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: 80)
        .frame(maxWidth: .infinity)
        .background(FluxItBarBackground())
    }
}
