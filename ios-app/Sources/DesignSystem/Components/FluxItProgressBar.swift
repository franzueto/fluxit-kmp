import SwiftUI

public struct FluxItProgressBar: View {
    private let progress: Double

    public init(progress: Double) {
        self.progress = max(0, min(1, progress))
    }

    public var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(FluxItTokens.Colors.dividerSubtle)
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .fill(FluxItTokens.Colors.primaryBlue)
                    .frame(width: geo.size.width * progress)
            }
        }
        .frame(height: 6)
        .accessibilityElement()
        .accessibilityLabel("Progress")
        .accessibilityValue("\(Int(progress * 100)) percent")
    }
}
