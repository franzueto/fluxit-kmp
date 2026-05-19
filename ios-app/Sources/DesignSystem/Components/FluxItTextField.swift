import SwiftUI

public struct FluxItTextField: View {
    @Binding private var text: String
    private let label: String
    private let placeholder: String
    private let singleLine: Bool
    private let minLines: Int

    public init(
        text: Binding<String>,
        label: String,
        placeholder: String = "",
        singleLine: Bool = true,
        minLines: Int = 1
    ) {
        self._text = text
        self.label = label
        self.placeholder = placeholder
        self.singleLine = singleLine
        self.minLines = minLines
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(FluxItTokens.Colors.textMuted)
            Group {
                if singleLine {
                    TextField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text, axis: .vertical)
                        .lineLimit(minLines...20)
                }
            }
            .font(.system(size: 16))
            .foregroundStyle(FluxItTokens.Colors.textPrimary)
            .tint(FluxItTokens.Colors.primaryBlue)
            .textFieldStyle(.plain)
            .accessibilityLabel(label)
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(FluxItTokens.Colors.surfaceCard)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}
