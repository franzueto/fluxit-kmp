import SwiftUI

public struct FluxItTextField: View {
    @Binding private var text: String
    private let label: String
    private let placeholder: String
    private let singleLine: Bool
    private let minLines: Int
    /// Optional external focus binding so callers can drive auto-focus and
    /// observe focus loss (Phase 09 Create-List name field). Applied directly to
    /// the inner `TextField` so `.focused`/`onChange` track the real focus state;
    /// `nil` for the common case that doesn't care.
    private let focused: FocusState<Bool>.Binding?

    public init(
        text: Binding<String>,
        label: String,
        placeholder: String = "",
        singleLine: Bool = true,
        minLines: Int = 1,
        focused: FocusState<Bool>.Binding? = nil
    ) {
        self._text = text
        self.label = label
        self.placeholder = placeholder
        self.singleLine = singleLine
        self.minLines = minLines
        self.focused = focused
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(FluxItTokens.Colors.textMuted)
            Group {
                if singleLine {
                    focusable(TextField(placeholder, text: $text))
                } else {
                    focusable(
                        TextField(placeholder, text: $text, axis: .vertical)
                            .lineLimit(minLines...20)
                    )
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

    /// Binds the caller's `FocusState` to the inner field when one was supplied;
    /// otherwise returns the field untouched (the `.focused` modifier only works
    /// applied directly to a focusable view, not a container).
    @ViewBuilder private func focusable(_ field: some View) -> some View {
        if let focused {
            field.focused(focused)
        } else {
            field
        }
    }
}
