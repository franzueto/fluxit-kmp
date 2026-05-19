# Phase 02 — Design System

> **Goal:** Encode FluxIt's visual language as a single source of truth that produces type-safe tokens for **both** Compose (Android) and SwiftUI (iOS), with the Inter typeface, Material Symbols Outlined iconography, and a small set of reusable primitives that every later feature phase consumes.

**Owner:** Mobile platform (design-system steward TBD)
**Depends on:** Phase 01 (`core-designsystem` module stub exists, `build-logic` ready).
**Blocks:** Phases 07–10 (all UI-bearing feature phases).
**Exit criteria (Definition of Done):**
- `core-designsystem` exposes `FluxItTheme` (Compose) and `FluxItTokens` (SwiftUI) generated from one shared token source; mismatch fails CI.
- All four screens in `/design` can be reproduced using only design-system primitives — no ad-hoc literals in feature modules.
- Konsist rule passes: no color/dimension/typography literals outside `core-designsystem`.
- A "Theme Gallery" debug screen renders every primitive on Android and iOS.
- `DESIGN.md` rebranded "Lumina Lists" → "FluxIt"; the `#2b7cee` primary color added to the YAML token map (currently missing).

---

## 1. Token source of truth

We need ONE place tokens live, then generate platform code. Decision is captured in **ADR-005** (drafted in this phase — see §11).

**Chosen approach (proposed):** hand-authored `tokens.json` checked in at `core-designsystem/tokens/tokens.json` (W3C Design Tokens Community Group format), with a small Gradle code-generation task that emits:
- `FluxItColors.kt`, `FluxItTypography.kt`, `FluxItShapes.kt`, `FluxItSpacing.kt`, `FluxItElevation.kt` for Compose.
- `FluxItTokens.swift` (an `enum FluxItTokens { enum Color, enum Spacing… }`) for SwiftUI, written to `ios-app/Generated/`.

Rationale: keeps both platforms honest, lets designers edit JSON without touching code, and doesn't pull in Style Dictionary as a Node dependency (we'll write a ~200-line Kotlin generator instead — small, reviewable, no JS toolchain in CI).

- [x] Create `core-designsystem/tokens/tokens.json` populated from `DESIGN.md` (see §2 for the full token list). _Landed as subset (primary + neutrals + semantic surfaces + 5 type styles + shapes + spacing + elevation level0/1) per the agreed scope; accents, category palette, motion, level2.fab, blur.backdrop deferred until a feature phase needs them._
- [x] Implement Gradle task `:core:core-designsystem:generateTokens` that reads `tokens.json` and emits Compose + Swift sources. _Lives in `build-logic/src/main/kotlin/dev/franzueto/fluxit/tokens/` (parser, two emitters, task); wired onto core-designsystem by the precompiled plugin `fluxit.designsystem.tokens`._
- [x] Wire `generateTokens` as a dependency of `compileKotlin` (Compose side) and as an Xcode build phase (Swift side). _Compose side: precompiled plugin attaches `compile*Kotlin*` `dependsOn(generateTokens)` and registers `build/generated/source/tokens/androidMain` on the androidMain source set. iOS side: `scripts/build-ios.sh` invokes `:core:core-designsystem:generateTokens` before xcodegen. Adding a native Xcode "Run Script" phase is deferred to Phase 15._
- [x] Add a CI check `verifyTokensInSync` that re-runs generation and fails if working tree is dirty. _With generated files gitignored the original "dirty tree" check is moot — `verifyTokensInSync` instead re-runs the generator and asserts every expected output file is present and non-trivial, catching tokens.json structural breakage and silent emitter regressions._
- [x] Document the workflow in `core-designsystem/README.md`: edit JSON → run task → commit generated files. _Landed; note the wording is "edit JSON → run task → use the generated APIs" (generated files are not committed)._

## 2. Token catalog (from `DESIGN.md` + screen audit)

> **Subset scope (agreed 2026-05-18, retrospectively ticked alongside §1):** the v1 token set encoded in `tokens.json` covers primary + neutrals + semantic surfaces, the 5 Inter type styles, the shape scale, the spacing scale, and `elevation.level0`/`level1`. Unchecked rows below are **deferred per Phase 02 subset scope** — they will be added when the feature phase that first needs them lands (accents: feature phases; category palette + sky: §9 Create List; `level2.fab`/`blur.backdrop`: §5 primitives + §7 backdrop blur; motion: §5/§7).

Encode at minimum:

**Colors**
- [x] `background.dark` = `#101822`
- [x] `surface.card` = `#1e2632`
- [x] `surface.search` = `#1e2632`
- [x] `surface.cardMuted` = `#1e2632` @ 50% (resting list-item state per DESIGN.md)
- [x] `text.primary` = `#ffffff`
- [x] `text.muted` = `#9da8b9`
- [ ] `accent.orange` = `#f97316` _(deferred per subset scope)_
- [ ] `accent.emerald` = `#10b981` _(deferred per subset scope)_
- [ ] `accent.rose` = `#f43f5e` _(deferred per subset scope)_
- [ ] `accent.indigo` = `#6366f1` _(deferred per subset scope)_
- [x] **`primary.blue` = `#2b7cee`** ← was missing in original YAML; landed in `tokens.json` (used for FAB, active tab, primary CTAs)
- [x] `primary.blueShadow` = `#2b7cee` @ 40% (FAB shadow tint)
- [x] `divider.subtle` = computed from `text.muted` @ 12%
- [ ] Category palette (used in Create List swatches): blue/rose/emerald/orange/indigo/sky — sky hex resolved as `#38bdf8` (see "Resolved decisions" below); not yet in `tokens.json` _(deferred per subset scope — target §9 Create List)_

**Typography (font: Inter)**
- [x] `display.lg` — 32 / 700 / 1.2 / -0.02em
- [x] `title.md` — 18 / 600 / 1.4
- [x] `body.md` — 16 / 400 / 1.5
- [x] `label.sm` — 14 / 400 / 1.4
- [x] `caption.xs` — 10 / 500 / 1.0

**Shapes (corner radii)**
- [x] `sm` 4dp · `default` 8dp · `md` 12dp · `lg` 16dp · `xl` 24dp · `full` 9999dp

**Spacing**
- [x] `containerPadding` 16dp
- [x] `stackGap` 8dp
- [x] `itemPaddingX` 16dp · `itemPaddingY` 12dp
- [x] `fabOffset` 32dp
- [x] Plus a 4dp base scale: `xs=4, sm=8, md=12, lg=16, xl=24, 2xl=32, 3xl=48`

**Elevation (Tonal + Ambient)**
- [x] `level0` (background)
- [x] `level1` (surface) — no shadow, only tonal lift
- [ ] `level2.fab` — primary-tinted shadow (`primary.blueShadow`, 24dp blur, 8dp y-offset) _(deferred per subset scope — target §5 `FluxItFab`)_
- [ ] `blur.backdrop` — 14dp (header + tab bar) _(deferred per subset scope — target §7 backdrop blur)_

**Motion (proposed defaults; confirm)**
- [ ] `duration.fast` 120ms · `duration.standard` 200ms · `duration.emphasized` 320ms _(deferred per subset scope)_
- [ ] `easing.standard` cubic(0.2, 0.0, 0, 1) _(deferred per subset scope)_
- [ ] `easing.emphasized` cubic(0.2, 0.0, 0, 1.0) _(deferred per subset scope)_

## 3. Typography pipeline

- [x] Bundle Inter (Regular, Medium, SemiBold, Bold) as variable font `Inter-Variable.ttf` in both apps. _Source: rsms/inter v4.1 (sha256 9883fdd…b11e); single `InterVariable.ttf` (880KB) shipped on both platforms._
  - Android: `core-designsystem/src/androidMain/res/font/inter_variable.ttf` + `FontFamily.Companion.Inter` extension using `FontVariation.Settings(FontVariation.weight(…))` per Compose `FontWeight` (Normal=400, Medium=500, SemiBold=600, Bold=700). One resource, axis-driven weight selection.
  - iOS: `ios-app/Resources/Inter-Variable.ttf` + `UIAppFonts` entry in `Info.plist` (via `project.yml` `info.properties`). SwiftEmitter uses `Font.custom("Inter", size: …).weight(…)` — `.weight()` picks the right `wght` axis on the variable font (iOS 16+).
- [x] Verify license file (Inter is OFL — include `OFL.txt` next to the font on both platforms).
  - Android: `assets/fonts/OFL.txt` (AGP rejects non-font files in `res/font/`; relocated to `assets/fonts/` so it still ships in the APK and an in-app Licenses screen can read it via `AssetManager`).
  - iOS: `ios-app/Resources/OFL.txt`.
- [x] Compose: `FluxItTypography` exposes `displayLg`, `titleMd`, `bodyMd`, `labelSm`, `captionXs` as `TextStyle`. _Generated by `KotlinEmitter.emitTypography`; each style backed by `FontFamily.Inter`._
- [ ] SwiftUI: `Font.fluxIt.titleMd` etc. via `extension Font`. _**Deviation from spec — pending decision.** Generator emits `FluxItTokens.Typography.titleMd` as a `TypographyStyle` struct (`font` + `lineHeight` + `tracking`) because SwiftUI's `Font` can't carry lineHeight or letterSpacing. The struct is a superset of the spec's API. A thin `Font.fluxIt.*` convenience accessor on top of the struct (for callers that don't need lineHeight/tracking) can be added as a small hand-authored Swift file in `ios-app/Sources/` if/when a feature phase wants it. Surfacing now so we either (a) accept the deviation and update spec, or (b) add the accessor before §5 primitives consume typography._
- [ ] Snapshot test renders all five styles on both platforms. _Deferred to Phase 14 (snapshot harness — Paparazzi for Android, swift-snapshot-testing for iOS — is part of Phase 14's test-strategy work; pulling it into §3 would drag harness choices forward under typography pressure)._

## 4. Iconography (Material Symbols Outlined)

- [x] Decision: **vectorize the ~25 icons we use**, sourced from Material Symbols Outlined (weight 400, grade 0, opsz 24). Decision formalized in **ADR-005a** (Accepted 2026-05-19). 25 SVGs live under `core/core-designsystem/icons/` with the upstream Apache-2.0 license and a filename → Material-Symbols-name attribution table in `ATTRIBUTION.md`.
- [x] Compose: each icon as `FluxItIcons.Cart`, `FluxItIcons.Home`… returning `ImageVector`, generated by `:core:core-designsystem:generateIcons` from `core-designsystem/icons/*.svg`. _Generator emits a single `FluxItIcons.kt` under `build/generated/source/icons/androidMain/dev/franzueto/fluxit/core/designsystem/icons/`. Uses `ImageVector.Builder` directly (not `materialIcon`, which hardcodes a 24×24 viewport — Material Symbols ship on 960×960). Absolute Y coordinates are shifted by `-viewBoxMinY` so the Compose viewport anchors at (0,0); relative deltas pass through unshifted. Each icon is memoized via a top-level `private var _<lowerCamel>: ImageVector? = null` backing field (mirroring the official Material Icons codegen pattern). `compileKotlin` `dependsOn generateIcons`._
- [x] SwiftUI: SF Symbols *won't* match exactly — ship the same SVGs as `Asset Catalog` symbol images. _Generator writes `ios-app/Resources/FluxItIcons.xcassets/ic-<name>.imageset/{Contents.json + ic-<name>.svg}` per icon, plus a sibling `ios-app/Generated/icons/FluxItIcons.swift` Swift accessor file (a `public extension FluxItTokens { enum Icons { … } }`). Imagesets use `template-rendering-intent: template` so `.foregroundStyle()` tints them, and `preserves-vector-representation: true` so they stay crisp when scaled. The `ic-` prefix avoids collisions with future non-icon image assets. Accessors are of the form `FluxItTokens.Icons.cart: Image = Image("ic-cart")`. `scripts/build-ios.sh` invokes `generateIcons` alongside `generateTokens` before xcodegen; `ios-app/project.yml` already includes `Resources` recursively, with `ASSETCATALOG_COMPILER_APPICON_NAME` cleared so `actool` doesn't fail looking for an AppIcon (Phase 17 work)._
- [x] Active-state fills: tab-bar active tab uses filled variants; expose `FluxItIcons.ListsFilled` etc. for the four tab icons. _4 filled-state variants shipped: `list-filled.svg`, `calendar-filled.svg`, `star-filled.svg`, `account-filled.svg` (Material Symbols Fill=1). Generator produces `FluxItIcons.ListFilled` / `.CalendarFilled` / `.StarFilled` / `.AccountFilled` on Compose and `FluxItTokens.Icons.listFilled` / `.calendarFilled` / `.starFilled` / `.accountFilled` on SwiftUI._

## 5. Reusable primitives (`core-designsystem` API)

Each primitive ships in **both** Compose and SwiftUI with identical name and prop semantics. Cross-platform parity is enforced by snapshot tests.

- [x] **`FluxItScaffold`** — applies `background.dark`, safe-area handling, optional sticky header + tab bar slots with `backdrop-blur-md`. _Compose: `core/core-designsystem/src/androidMain/.../components/FluxItScaffold.kt` (Material3 `Scaffold` with `containerColor = FluxItColors.backgroundDark`, `contentColor = FluxItColors.textPrimary`, slots for `topBar`/`bottomBar`). SwiftUI: `ios-app/Sources/DesignSystem/Components/FluxItScaffold.swift` (`ZStack` over `FluxItTokens.Colors.backgroundDark.ignoresSafeArea()` with `safeAreaInset(edge: .top/.bottom)` for slot equivalents). Backdrop blur is layered on by `FluxItTopBar` / `FluxItBottomTabBar` themselves (§7 finalizes the perf path); the scaffold is just the chrome._
- [x] **`FluxItTopBar`**
  - Variant A: large title (`display.lg`), trailing icon button (settings) — used on Lists Dashboard.
  - Variant B: centered title + leading text-button (e.g., "‹ Lists") + trailing icon button — used on List Detail / Edit Item.
  _Shipped as two top-level primitives: `FluxItTopBarLarge` / `FluxItTopBarCentered` (Compose + SwiftUI). Bar background uses the §7-resolved opaque fallback (`surface.card @ 90%`) on Android and `.ultraThinMaterial` on iOS until §7 finalizes the blur perf path._
- [x] **`FluxItBottomTabBar`** — 4 tabs, 80dp height, blur backdrop, centered icon+caption stack, active-state fill + `primary.blue` tint. _Generic over a `List<FluxItTabItem>` (icon + activeIcon + label) on both platforms; selection-state tinting via `primary.blue` / `text.muted`. Same backdrop strategy as the top bar._
- [ ] **`FluxItSearchField`** — full-width, leading search icon, no border, `surface.search` fill, `rounded-xl`, placeholder uses `text.muted`.
- [ ] **`FluxItCard`** — surface card with `rounded-xl`, optional resting-state 50% opacity, press-state 100%.
- [ ] **`FluxItListItem`** — three-slot layout: 56dp leading icon container with 20%-opacity tint, title + subtitle stack, trailing slot. Variants:
  - Dashboard list-item (icon container colored, trash + chevron trailing).
  - Detail to-buy item (radio leading, chevron trailing).
  - Detail completed item (filled-circle check leading, strikethrough title, trash trailing).
- [ ] **`FluxItProgressBar`** — slim `primary.blue` linear progress with rounded caps; used on List Detail header (`13/20`).
- [ ] **`FluxItFab`** — 64dp circle, `primary.blue`, `level2.fab` shadow, plus icon. Center-docked variant for tab bar.
- [ ] **`FluxItIconChip`** — used in Create List icon picker: 80dp rounded square, selected state shows `primary.blue` border + tint.
- [ ] **`FluxItColorSwatch`** — circle with optional ring for selected state.
- [ ] **`FluxItPrimaryButton`** — full-width, `primary.blue`, `body.md` semibold white text. Disabled state at 40% opacity.
- [ ] **`FluxItTextField`** — labeled (uppercase `caption.xs` muted label above), surface fill, `rounded-md`. Single-line and multi-line variants (Edit Item description).
- [ ] **`FluxItInlineComposer`** — bottom-anchored "+ Add new item…" pill + circular submit button on the right (List Detail screen).
- [ ] **`FluxItSectionHeader`** — uppercase muted label + optional trailing text-button ("Hide").
- [ ] **`FluxItEmptyState`** — used by Calendar/Starred placeholders (per ADR-004) and by the Lists Dashboard before any list exists.
- [ ] **`FluxItDestructiveButton`** — outlined variant in `accent.rose` with trash icon (Edit Item delete).
- [ ] **`FluxItSwipeRow`** — gesture container that reveals a rose-tinted destructive action on swipe. Android: built on `SwipeToDismissBox` with a custom background. iOS: thin wrapper around native `.swipeActions(edge: .trailing) { Button(role: .destructive) … }`. Backfilled from Phase 07 (used by dashboard rows; reusable for any list with destructive row actions).

## 6. Theme + dark-mode-only policy

- [x] DESIGN.md is dark-mode-first. v1 ships **dark only** — no light theme, no `isSystemInDarkTheme()` switching. _Ratified as **ADR-005b** (2026-05-19)._
- [x] Compose: `FluxItTheme` provides a fixed `darkColorScheme` plus our token objects via `CompositionLocal`. _Landed at `core/core-designsystem/src/androidMain/kotlin/dev/franzueto/fluxit/core/designsystem/theme/FluxItTheme.kt` — wraps `MaterialTheme(darkColorScheme=…, typography=…)` mapped from `FluxItColors`/`FluxItTypography`, and provides 5 `staticCompositionLocalOf` locals (`LocalFluxItColors`, `LocalFluxItTypography`, `LocalFluxItShapes`, `LocalFluxItSpacing`, `LocalFluxItElevation`). `material3` dep added to `androidMain` for `MaterialTheme`/`darkColorScheme`._
- [x] SwiftUI: lock `preferredColorScheme(.dark)` at the app root. _Applied to `ContentView` inside `WindowGroup` in `ios-app/Sources/FluxItApp.swift`._
- [x] Document the rationale + future light-mode reservation in ADR (paired with §11 design-token ADR or a separate one). _**ADR-005b** Accepted on 2026-05-19; supersedes the §11 row 3 anticipated entry._

## 7. Backdrop blur on header + tab bar

- [ ] Compose: use `Modifier.blur()` from compose-ui 1.7+ on a behind-content snapshot, **or** a `Surface` with `surface.card` @ 80% opacity if blur cost is too high on mid-range Android. Benchmark before committing — both options stubbed.
- [ ] SwiftUI: `.background(.ultraThinMaterial)` on the header/tab-bar overlays.
- [ ] Verify on Pixel 6a + iPhone 12 mini that scrolling stays at 60fps with header blur.

## 8. Accessibility

- [ ] Every color pair we ship as text-on-surface meets WCAG AA (4.5:1 for body, 3:1 for large/headlines). Verify:
  - `text.primary` on `background.dark` ✅ (computed)
  - `text.muted` on `background.dark` — computed at ~5.6:1, ✅
  - `text.primary` on `primary.blue` — verify (likely ~4.7:1) ✅
- [ ] All primitives expose semantic labels (Compose `contentDescription`, SwiftUI `.accessibilityLabel`).
- [ ] `FluxItIconChip`, `FluxItColorSwatch` — selected state communicated beyond color (border + a11y trait `isSelected`).
- [ ] Hit targets ≥ 44pt / 48dp for all interactive elements.
- [ ] Dynamic type: respect platform text scaling up to 130%; verify list items don't truncate at that scale.

## 9. Theme Gallery debug screen

- [ ] `:core:core-designsystem` ships a `ThemeGalleryScreen()` (Compose) and `ThemeGalleryView` (SwiftUI) inside a `debug` source set.
- [ ] Renders every primitive in every variant + the full color/type/spacing token grid.
- [ ] Reachable from each app via a hidden long-press on the Account tab in debug builds.
- [ ] Snapshot-tested: one golden image per platform, regenerated only on intentional change (Phase 14 wires the comparator).

## 10. Brand cleanup

- [x] Update `DESIGN.md` frontmatter: `name: Lumina Lists` → `name: FluxIt`.
- [x] Add the missing `primary-blue: '#2b7cee'` entry to the `colors` map in `DESIGN.md`. _Inserted between `text-muted` and the `accent-*` block._
- [x] Sweep prose body of `DESIGN.md` for "Lumina Lists" mentions and replace. _One occurrence in "Brand & Style" rewritten; no other prose mentions._
- [x] Add a one-line note at the top of `DESIGN.md`: "Token source of truth lives in `core-designsystem/tokens/tokens.json`; this file is the human-readable narrative." _Added as a callout block above "Brand & Style"._
- [x] Confirm with product whether `/design` mockup PNGs need rebrand re-export (no in-screen "Lumina" copy is visible, so likely not). _Confirmed: `grep -ri lumina design/` returns zero hits across the source HTML for all four screens — PNGs render from that HTML, so no re-export needed._

## 11. ADRs to write in this phase

- [ ] **ADR-005** — Design token pipeline (JSON-as-SoT + Kotlin generator vs. Style Dictionary vs. hand-maintained-twice). Document why we picked the chosen approach and the rejected alternatives.
- [ ] **ADR-005a** (or merge into 005) — Iconography: vectorized set vs. Material Symbols variable font.
- [x] **ADR-005b** (or merge into 005) — Dark-mode-only for v1; reserve namespace for light tokens. _Accepted 2026-05-19._

## 12. Sanity tests

- [ ] Unit test: every Compose token in `FluxItColors` has a Swift counterpart (parsed from generated `FluxItTokens.swift`); fail if any missing.
- [ ] Snapshot test: Theme Gallery on Android + iOS — golden images checked in.
- [ ] Konsist rule: no `Color(0x…)`, `dp(…)`, raw `sp(…)`, or `Font(…)` literals outside `core-designsystem`.
- [ ] A11y test: `text.muted` vs. every surface passes 4.5:1 (or marked as decorative-only with a code comment + a11y override).

## 13. Hand-off checklist (gate to Phase 03)

- [ ] All checkboxes above are ✅.
- [ ] Theme Gallery PR includes side-by-side Android + iOS screenshots.
- [ ] `MASTER_PLAN.md` updated: Phase 02 → 🟢, "▶ Next Step" advanced to Phase 03.
- [ ] `00_DECISIONS.md` updated with ADR-005 (and 005a/b) accepted.

---

## Resolved decisions for this phase (2026-05-11)

- ✅ **Sky swatch:** `#38bdf8` (Tailwind sky-400). Add to category palette in §2.
- ✅ **Android blur fallback:** opaque `surface.card` @ 90% when `RenderEffect.createBlurEffect` is unavailable (API < 31) or the perf budget in §7 is missed on the target device. No intermediate "reduced blur" branch.
- ✅ **Iconography:** vectorize the ~25 icons we actually use; SVGs live in `core-designsystem/icons/` and generate `FluxItIcons.*` (Compose `ImageVector`) + iOS asset-catalog symbol set. New icons = new SVG + regen.
- ✅ **Inter format:** ship `Inter-Variable.ttf` (single ~750KB file). Weight axis exposed on both platforms.
- ✅ **Light theme:** v1 is dark-only (locked via ADR-005b in §11). Token namespace reserved (`tokens.json` keys grouped under `light`/`dark`, with `light` empty for now).
