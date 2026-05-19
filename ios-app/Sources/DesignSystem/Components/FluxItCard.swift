import SwiftUI

public struct FluxItCard<Content: View>: View {
    private let resting: Bool
    private let content: Content

    public init(resting: Bool = false, @ViewBuilder content: () -> Content) {
        self.resting = resting
        self.content = content()
    }

    public var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                resting
                    ? FluxItTokens.Colors.surfaceCardMuted
                    : FluxItTokens.Colors.surfaceCard
            )
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
