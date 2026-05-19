import SwiftUI

// Phase 02 §5: SwiftUI mirror of Compose FluxItScaffold. Applies
// background.dark + safe-area handling + optional sticky top/bottom slots.
// Header/tab-bar blur is layered on by FluxItTopBar / FluxItBottomTabBar
// themselves (§7); the scaffold is just the chrome.

public struct FluxItScaffold<TopBar: View, BottomBar: View, Content: View>: View {
    private let topBar: TopBar
    private let bottomBar: BottomBar
    private let content: Content

    public init(
        @ViewBuilder topBar: () -> TopBar = { EmptyView() },
        @ViewBuilder bottomBar: () -> BottomBar = { EmptyView() },
        @ViewBuilder content: () -> Content
    ) {
        self.topBar = topBar()
        self.bottomBar = bottomBar()
        self.content = content()
    }

    public var body: some View {
        ZStack(alignment: .top) {
            FluxItTokens.Colors.backgroundDark
                .ignoresSafeArea()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .foregroundStyle(FluxItTokens.Colors.textPrimary)
                .safeAreaInset(edge: .top, spacing: 0) { topBar }
                .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar }
        }
    }
}
