import SwiftUI

public struct FluxItSearchField: View {
    @Binding private var text: String
    private let searchIcon: Image
    private let placeholder: String

    public init(text: Binding<String>, searchIcon: Image, placeholder: String = "Search") {
        self._text = text
        self.searchIcon = searchIcon
        self.placeholder = placeholder
    }

    public var body: some View {
        HStack(spacing: 8) {
            searchIcon.foregroundStyle(FluxItTokens.Colors.textMuted)
            ZStack(alignment: .leading) {
                if text.isEmpty {
                    Text(placeholder)
                        .font(.system(size: 16))
                        .foregroundStyle(FluxItTokens.Colors.textMuted)
                }
                TextField("", text: $text)
                    .font(.system(size: 16))
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
                    .tint(FluxItTokens.Colors.primaryBlue)
                    .textFieldStyle(.plain)
                    .accessibilityLabel(placeholder)
            }
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity)
        .frame(height: 48)
        .background(FluxItTokens.Colors.surfaceSearch)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
