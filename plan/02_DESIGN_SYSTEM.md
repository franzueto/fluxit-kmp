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
- [ ] Implement Gradle task `:core:core-designsystem:generateTokens` that reads `tokens.json` and emits Compose + Swift sources.
- [ ] Wire `generateTokens` as a dependency of `compileKotlin` (Compose side) and as an Xcode build phase (Swift side).
- [ ] Add a CI check `verifyTokensInSync` that re-runs generation and fails if working tree is dirty.
- [ ] Document the workflow in `core-designsystem/README.md`: edit JSON → run task → commit generated files.

## 2. Token catalog (from `DESIGN.md` + screen audit)

Encode at minimum:

**Colors**
- [ ] `background.dark` = `#101822`
- [ ] `surface.card` = `#1e2632`
- [ ] `surface.search` = `#1e2632`
- [ ] `surface.cardMuted` = `#1e2632` @ 50% (resting list-item state per DESIGN.md)
- [ ] `text.primary` = `#ffffff`
- [ ] `text.muted` = `#9da8b9`
- [ ] `accent.orange` = `#f97316`
- [ ] `accent.emerald` = `#10b981`
- [ ] `accent.rose` = `#f43f5e`
- [ ] `accent.indigo` = `#6366f1`
- [ ] **`primary.blue` = `#2b7cee`** ← missing in current YAML; add it (used for FAB, active tab, primary CTAs)
- [ ] `primary.blueShadow` = `#2b7cee` @ 40% (FAB shadow tint)
- [ ] `divider.subtle` = computed from `text.muted` @ 12%
- [ ] Category palette (used in Create List swatches): blue/rose/emerald/orange/indigo/sky — confirm "sky" hex with design (proposal: `#38bdf8`).

**Typography (font: Inter)**
- [ ] `display.lg` — 32 / 700 / 1.2 / -0.02em
- [ ] `title.md` — 18 / 600 / 1.4
- [ ] `body.md` — 16 / 400 / 1.5
- [ ] `label.sm` — 14 / 400 / 1.4
- [ ] `caption.xs` — 10 / 500 / 1.0

**Shapes (corner radii)**
- [ ] `sm` 4dp · `default` 8dp · `md` 12dp · `lg` 16dp · `xl` 24dp · `full` 9999dp

**Spacing**
- [ ] `containerPadding` 16dp
- [ ] `stackGap` 8dp
- [ ] `itemPaddingX` 16dp · `itemPaddingY` 12dp
- [ ] `fabOffset` 32dp
- [ ] Plus a 4dp base scale: `xs=4, sm=8, md=12, lg=16, xl=24, 2xl=32, 3xl=48`

**Elevation (Tonal + Ambient)**
- [ ] `level0` (background)
- [ ] `level1` (surface) — no shadow, only tonal lift
- [ ] `level2.fab` — primary-tinted shadow (`primary.blueShadow`, 24dp blur, 8dp y-offset)
- [ ] `blur.backdrop` — 14dp (header + tab bar)

**Motion (proposed defaults; confirm)**
- [ ] `duration.fast` 120ms · `duration.standard` 200ms · `duration.emphasized` 320ms
- [ ] `easing.standard` cubic(0.2, 0.0, 0, 1)
- [ ] `easing.emphasized` cubic(0.2, 0.0, 0, 1.0)

## 3. Typography pipeline

- [ ] Bundle Inter (Regular, Medium, SemiBold, Bold) as variable font `Inter-Variable.ttf` in both apps.
  - Android: `core-designsystem/src/androidMain/res/font/inter_variable.ttf`, declare a `FontFamily.Inter` with weight axis.
  - iOS: add to `ios-app/Resources/`, declare in `Info.plist` `UIAppFonts`, expose via `FluxItTokens.Font.inter(weight:size:)`.
- [ ] Verify license file (Inter is OFL — include `OFL.txt` next to the font on both platforms).
- [ ] Compose: `FluxItTypography` exposes `displayLg`, `titleMd`, `bodyMd`, `labelSm`, `captionXs` as `TextStyle`.
- [ ] SwiftUI: `Font.fluxIt.titleMd` etc. via `extension Font`.
- [ ] Snapshot test renders all five styles on both platforms.

## 4. Iconography (Material Symbols Outlined)

- [ ] Decision needed: ship as a font (`MaterialSymbolsOutlined-Variable.ttf`, ~3MB) **or** vectorize the small set we actually use (cart, home, briefcase, plane, fork-knife, dumbbell, star, more, trash, chevron, search, plus, check, bell, camera, settings, account, calendar, list, arrow-up).
  - Default proposal: **vectorize the ~25 icons we use** to avoid the font weight; add new icons as `.xml`/`.svg` per addition. Tracked in ADR (TBD this phase).
- [ ] Compose: each icon as `FluxItIcons.Cart`, `FluxItIcons.Home`… returning `ImageVector` (generated from SVG via `:core:core-designsystem:generateIcons` task or hand-authored `materialIcon { … }` blocks).
- [ ] SwiftUI: SF Symbols *won't* match exactly — ship the same SVGs as `Asset Catalog` symbol images.
- [ ] Active-state fills: tab-bar active tab uses filled variants; expose `FluxItIcons.ListsFilled` etc. for the four tab icons.

## 5. Reusable primitives (`core-designsystem` API)

Each primitive ships in **both** Compose and SwiftUI with identical name and prop semantics. Cross-platform parity is enforced by snapshot tests.

- [ ] **`FluxItScaffold`** — applies `background.dark`, safe-area handling, optional sticky header + tab bar slots with `backdrop-blur-md`.
- [ ] **`FluxItTopBar`**
  - Variant A: large title (`display.lg`), trailing icon button (settings) — used on Lists Dashboard.
  - Variant B: centered title + leading text-button (e.g., "‹ Lists") + trailing icon button — used on List Detail / Edit Item.
- [ ] **`FluxItBottomTabBar`** — 4 tabs, 80dp height, blur backdrop, centered icon+caption stack, active-state fill + `primary.blue` tint.
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

- [ ] DESIGN.md is dark-mode-first. v1 ships **dark only** — no light theme, no `isSystemInDarkTheme()` switching.
- [ ] Compose: `FluxItTheme` provides a fixed `darkColorScheme` plus our token objects via `CompositionLocal`.
- [ ] SwiftUI: lock `preferredColorScheme(.dark)` at the app root.
- [ ] Document the rationale + future light-mode reservation in ADR (paired with §11 design-token ADR or a separate one).

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

- [ ] Update `DESIGN.md` frontmatter: `name: Lumina Lists` → `name: FluxIt`.
- [ ] Add the missing `primary-blue: '#2b7cee'` entry to the `colors` map in `DESIGN.md`.
- [ ] Sweep prose body of `DESIGN.md` for "Lumina Lists" mentions and replace.
- [ ] Add a one-line note at the top of `DESIGN.md`: "Token source of truth lives in `core-designsystem/tokens/tokens.json`; this file is the human-readable narrative."
- [ ] Confirm with product whether `/design` mockup PNGs need rebrand re-export (no in-screen "Lumina" copy is visible, so likely not).

## 11. ADRs to write in this phase

- [ ] **ADR-005** — Design token pipeline (JSON-as-SoT + Kotlin generator vs. Style Dictionary vs. hand-maintained-twice). Document why we picked the chosen approach and the rejected alternatives.
- [ ] **ADR-005a** (or merge into 005) — Iconography: vectorized set vs. Material Symbols variable font.
- [ ] **ADR-005b** (or merge into 005) — Dark-mode-only for v1; reserve namespace for light tokens.

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
