# core-designsystem

FluxIt's design system owns the design tokens (color, type, shape, spacing, and
elevation), generated icons, and reusable Compose and SwiftUI primitives.

## Design tokens (ADR-005)

`tokens/tokens.json` is the **single source of truth** for every visual
constant in the app. It uses the
[W3C Design Tokens Community Group](https://design-tokens.github.io/community-group/format/)
JSON format.

### Workflow

1. **Edit `tokens/tokens.json`.** Add or change a token using the existing
   shape. The file groups everything under top-level theme keys (`light` /
   `dark`) with shared `font`, `shape`, and `spacing` at the root. v1 ships
   dark-only — keep `light` empty until a light theme is designed and validated.
2. **Regenerate.** Either run the generator directly or trigger a build
   that depends on it:

   ```bash
   # Explicit:
   ./gradlew :core:core-designsystem:generateTokens

   # Implicit (any Android build of this module):
   ./gradlew :core:core-designsystem:assembleDebug

   # iOS:
   scripts/build-ios.sh    # runs generateTokens before xcodegen
   ```

3. **Use the generated APIs** from feature code:

   ```kotlin
   // Compose
   import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
   import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing

   Box(Modifier.background(FluxItColors.surfaceCard).padding(FluxItSpacing.containerPadding))
   ```

   ```swift
   // SwiftUI
   Rectangle()
       .fill(FluxItTokens.Colors.surfaceCard)
       .padding(FluxItTokens.Spacing.containerPadding)
   ```

### Generated files (do not edit)

| Platform | Path                                                            | Gitignored? |
|----------|-----------------------------------------------------------------|-------------|
| Compose  | `core/core-designsystem/build/generated/source/tokens/androidMain/FluxIt*.kt` | yes (under `build/`) |
| SwiftUI  | `ios-app/Generated/FluxItTokens.swift`                          | yes (explicit) |

### CI guard

`./gradlew :core:core-designsystem:verifyTokensInSync` re-runs the
generator and asserts every expected output file is present and
non-trivial. Run automatically as part of CI's check pipeline.

### Generator limits

- DTCG token types supported: `color`, `dimension`, `fontFamily`,
  `typography`, `shadow`. No support for `border`, `gradient`,
  `transition`, or `strokeStyle`.
- Aliasing is limited to `fontFamily` references inside typography
  composites (`"fontFamily": "{font.family.inter}"`). Other token types
  must use literal values.
- No math/calc references (`{spacing.scale.md} * 2`) — write the
  resolved value.
- `$extensions` blocks are silently ignored.

Icon generation and the dark-only policy are documented in ADR-005a and
ADR-005b in [`docs/DECISIONS.md`](../../docs/DECISIONS.md).
