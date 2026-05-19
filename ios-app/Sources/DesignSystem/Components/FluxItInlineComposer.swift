import SwiftUI

public struct FluxItInlineComposer: View {
    @Binding private var text: String
    private let onSubmit: () -> Void
    private let submitIcon: Image
    private let placeholder: String

    public init(
        text: Binding<String>,
        onSubmit: @escaping () -> Void,
        submitIcon: Image,
        placeholder: String = "Add new item…"
    ) {
        self._text = text
        self.onSubmit = onSubmit
        self.submitIcon = submitIcon
        self.placeholder = placeholder
    }

    public var body: some View {
        HStack(spacing: 8) {
            ZStack(alignment: .leading) {
                if text.isEmpty {
                    Text(placeholder)
                        .font(.system(size: 16))
                        .foregroundStyle(FluxItTokens.Colors.textMuted)
                }
                TextField("", text: $text, onCommit: onSubmit)
                    .font(.system(size: 16))
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
                    .tint(FluxItTokens.Colors.primaryBlue)
                    .textFieldStyle(.plain)
            }
            Button(action: onSubmit) {
                submitIcon
                    .foregroundStyle(FluxItTokens.Colors.textPrimary)
                    .frame(width: 48, height: 48)
                    .background(FluxItTokens.Colors.primaryBlue)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Submit")
        }
        .padding(.leading, 20)
        .padding(.trailing, 6)
        .frame(maxWidth: .infinity)
        .frame(height: 56)
        .background(FluxItTokens.Colors.surfaceCard)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
    }
}
