# FluxIt — Architecture Decision Records (ADR Log)

> Living log. One section per decision. **Do not edit accepted ADRs in place** — supersede them with a new entry that references the old one.
>
> **Split threshold:** when this file passes ~15 ADRs *or* an ADR needs diagrams/long appendices, migrate to `plan/adr/NNN-slug.md` per-file format and replace each section here with a one-line link.

**Format**

```
### ADR-NNN — Title
- Status: Proposed | Accepted | Superseded by ADR-XXX | Deprecated
- Date: YYYY-MM-DD
- Context: …
- Decision: …
- Consequences: …
- Alternatives considered: …
```

---

### ADR-001 — Native UI per platform; share domain/data/state only
- **Status:** Accepted
- **Date:** 2026-05-09
- **Context:** Two source prompts conflict. `Architect-Prompt.md` mandates "Do NOT share UI" with Compose on Android and SwiftUI-ready iOS. `FluxIt_Architect_Prompt.md` asks me to consider Decompose/Voyager/Circuit (which are UI/navigation libraries that imply shared UI via Compose Multiplatform). FluxIt's brand emphasizes a "calming, sophisticated, dependable" feel — perceived native quality matters.
- **Decision:** Share **domain, data, and presentation state** (MVI stores) via Kotlin Multiplatform. Keep **UI native**: Jetpack Compose on Android, SwiftUI on iOS. Navigation is per-platform (Navigation Compose / `NavigationStack`).
- **Consequences:**
  - ➕ Best-in-class native feel, blur effects and platform gestures behave correctly with no CMP escape hatches.
  - ➕ Eliminates Decompose/Voyager/Circuit from v1 stack.
  - ➕ iOS team can ship without learning Compose semantics.
  - ➖ Two UI implementations to maintain. Each new screen costs Compose + SwiftUI work.
  - ➖ Mitigation: shared MVI stores keep behavior identical; design tokens generated once and mirrored to both platforms (see ADR-005 in design-system phase).
- **Alternatives considered:**
  - Compose Multiplatform everywhere — faster delivery, but glassmorphic/blur surfaces and tab-bar chrome are still rough on iOS today.
  - Hybrid (CMP + native for sensitive flows) — highest complexity, deferred until we have a proven pain point in v2.

---

### ADR-002 — Brand name is "FluxIt"
- **Status:** Accepted
- **Date:** 2026-05-09
- **Context:** `DESIGN.md` frontmatter declares `name: Lumina Lists`, but the project, repo, and `FluxIt_Architect_Prompt.md` all say FluxIt. The product owner confirmed FluxIt is canonical and Lumina Lists was a draft.
- **Decision:** "FluxIt" is the user-facing product name. Update `DESIGN.md` frontmatter as part of Phase 02 (Design System). Package IDs: `com.fluxit.android` / `com.fluxit.ios`. Module prefix: `fluxit-` is **not** used in module names (modules are named by capability, e.g. `feature-lists`).
- **Consequences:**
  - ➕ One name across code, store listings, analytics events.
  - ➖ All references to "Lumina" in design assets must be swept (caption text, brand prose). Tracked in Phase 02.
- **Alternatives considered:** Two-tier branding (FluxIt platform / Lumina Lists app) — rejected as gratuitous complexity for a single-app product.

---

### ADR-003 — v1 is local-only; no backend, no auth, no networking
- **Status:** Accepted
- **Date:** 2026-05-09
- **Context:** No backend has been specified. Multi-device sync, auth, and conflict resolution are large subsystems that would dominate v1 scope. The product hypothesis (high-performance list-making) can be validated on a single device.
- **Decision:** v1 ships with SQLDelight on-device only. No Ktor client, no auth screens, no sync queue. **Reminders are local notifications only** (WorkManager / `UNUserNotificationCenter`). Photos stored in app sandbox; URIs in DB.
- **Consequences:**
  - ➕ Removes Ktor, Store5, OAuth, conflict-resolution code from v1.
  - ➕ Faster path to TestFlight / Play Internal track.
  - ➖ No cross-device continuity in v1 — must be communicated in onboarding.
  - ➖ Photo loss risk if user uninstalls; document in Phase 10.
  - 🔁 Repository contracts in `domain` are written **as if** a backend exists (suspending, returning `Result`) so v2 sync can swap implementations without touching call sites.
- **Alternatives considered:** Firebase/Supabase BaaS (deferred to v2), custom backend (no spec).

---

### ADR-004 — v1 ships only "Lists" and "Account" tabs; Calendar & Starred deferred
- **Status:** Accepted
- **Date:** 2026-05-09
- **Context:** The dashboard mockup shows a 4-tab bar (Lists, Calendar, Starred, Account). Only Lists has a designed flow. Calendar implies an agenda/reminder timeline subsystem; Starred implies a cross-list filter and a `starred` boolean on items/lists.
- **Decision:** Render all four tab icons in v1 to preserve design fidelity, but Calendar and Starred route to a "Coming soon" placeholder screen using the design system's empty-state pattern. Lists and Account are fully functional.
- **Consequences:**
  - ➕ Honors the visual design without committing to undesigned subsystems.
  - ➕ Cuts ~30% of feature work from v1.
  - ➖ Two dead-end tabs may be perceived as broken — placeholder copy must be intentional ("Coming in a future update").
  - 🔁 Data model still includes `is_starred` on lists/items so v2 Starred ships without a migration.
- **Alternatives considered:** Hide tabs (rejected: design-system hit), build all four (rejected: scope), Lists+Starred (rejected: marginal Starred value without Calendar).

---

### ADR-012 — Package ID base is `dev.franzueto.fluxit` (supersedes package-ID clause of ADR-002)
- **Status:** Accepted
- **Date:** 2026-05-13
- **Context:** ADR-002 fixed package IDs at `com.fluxit.android` / `com.fluxit.ios` on the assumption that `fluxit.com` (or similar `com.fluxit` reverse-DNS) was an owned or acquirable namespace. At Phase 01 §4 implementation time the project owner confirmed they do **not** own `fluxit.com`; they own the personal domain `franzueto.dev`. This is a portfolio app demonstrating professional experience, intended for Play / App Store publication.
- **Decision:** Use `dev.franzueto.fluxit` as the canonical reverse-DNS base for all package, namespace, and bundle identifiers.
  - Android `applicationId` = `dev.franzueto.fluxit`
  - iOS bundle ID = `dev.franzueto.fluxit`
  - Android library namespaces = `dev.franzueto.fluxit.<module-path>` (e.g. `dev.franzueto.fluxit.core.designsystem`, `dev.franzueto.fluxit.feature.lists`)
  - Kotlin packages mirror the namespaces.
  - The brand name "FluxIt" from ADR-002 is unchanged — only the package-ID clause is superseded.
- **Consequences:**
  - ➕ ID space is anchored to a domain we actually own; no risk of Play/App Store namespace collision with a future `fluxit.com` operator.
  - ➕ Portfolio signal: store-listing reviewers can verify the developer owns the domain.
  - ➖ Slightly verbose package paths in imports (`dev.franzueto.fluxit.feature.lists.…`).
  - 🔁 All future code, manifests, `Info.plist`, signing configs, analytics namespaces, and deep-link URIs derive from this base. No code currently exists referencing the old IDs, so the supersede has no migration cost.
- **Alternatives considered:**
  - Keep `com.fluxit` and risk later collision (rejected — see Context).
  - Acquire `fluxit.com` (out of scope; not a current cost the project owner wants to take on).
  - Two-tier (`dev.franzueto.fluxit` for app, `com.fluxit` for libraries) — rejected as pointlessly inconsistent.

---

### ADR-013 — Platform minimums: Android `minSdk = 26`, iOS deployment target `16.0`
- **Status:** Accepted
- **Date:** 2026-05-14
- **Context:** Phase 01 §6/§7 needed concrete platform minimums to wire the build. Implementation landed `android-min-sdk = 26` in the version catalog (with `compileSdk = 35`, `targetSdk = 35`) and `deploymentTarget: "16.0"` in `ios-app/project.yml`, matching the "default proposal" lines in Phase 01's Open Questions. These values were never ratified into the ADR log, leaving §12's Open-Questions reconciliation step blocked. Resolving now (during §9, Documentation seeds) so `docs/ARCHITECTURE.md` can cite an accepted ADR rather than a "TBD".
- **Decision:**
  - **Android:** `minSdk = 26` (Android 8.0, "Oreo"). `compileSdk = 35` and `targetSdk = 35` (current stable as of catalog stewardship). Locked in `gradle/libs.versions.toml`; any change requires a superseding ADR.
  - **iOS:** Deployment target `16.0`. Locked in `ios-app/project.yml`; any change requires a superseding ADR.
- **Consequences:**
  - ➕ Android 26 unblocks adaptive icons (Phase 02 brand assets) and `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` constants needed by Phase 13 reminders without back-compat shims.
  - ➕ iOS 16 unblocks SwiftUI `NavigationStack` (Phase 07 nav model), `PHPicker` improvements (Phase 10 photo picking), and the `Observable` macro for any SwiftUI view-model bridges.
  - ➕ Coverage as of 2026-05-14: Android 26+ ≈ 95% of active devices; iOS 16+ ≈ 96% of active devices — both comfortably inside the "drop the long tail" zone for a v1 portfolio launch.
  - ➖ Cuts off Android 7.x (≈3–4% tail) and iOS 15 (≈2% tail). Acceptable for v1; no enterprise/compliance constraint forces a wider net.
  - 🔁 Phase 15 CI matrix can pin a single Android API level (26) for instrumentation tests in v1; expand later only if a regression report demands it.
- **Alternatives considered:**
  - `minSdk = 24` / iOS 15 — buys ~3% reach, costs adaptive-icon back-compat and `Observable` workarounds. Rejected.
  - `minSdk = 28` / iOS 17 — eliminates more legacy code paths but cuts ~10–12% of devices on each platform. Premature for a launch product.
  - Defer the lock to Phase 15 — rejected: leaving it open means every later phase has to qualify "if minSdk stays 26 then…", which is friction with no benefit.
- **Resolves Open Questions in Phase 01:** "Min Android SDK?" and "Min iOS version?" — both can be checked off in §12 hand-off.

---

### ADR-005 — Design token pipeline: hand-authored `tokens.json` + Gradle-resident Kotlin generator
- **Status:** Accepted (flipped from Proposed on 2026-05-18 after first clean Compose + SwiftUI round-trip and a green `assembleDebug`)
- **Date:** 2026-05-18
- **Context:** Phase 02 requires a single source of truth for color/type/shape/spacing/elevation tokens that emits both Compose (Kotlin) and SwiftUI (Swift) consumables. ADR-001 locked native UI on each platform, so the same token must surface twice in two different type systems without drift. Three concrete approaches are on the table:
  1. **Style Dictionary** (Amazon, JS) — the industry default for multi-platform token export. Mature transforms, large ecosystem, but pulls a Node toolchain into CI for a single ~one-file emission step.
  2. **Hand-maintained-twice** — author `FluxItColors.kt` and `FluxItTokens.swift` directly, code-review for parity. Zero tooling, infinite drift surface.
  3. **JSON-as-SoT + a small in-repo Kotlin generator** — `core-designsystem/tokens/tokens.json` in W3C Design Tokens Community Group format, with a Gradle task in `build-logic` that emits both Kotlin and Swift outputs. ~200 lines of generator code, no extra toolchain.
  The repo already runs Gradle on every CI job and already produces a Kotlin/JVM `build-logic` module; adding a generator there is incremental. The repo does not currently run Node anywhere; adopting Style Dictionary would be the first dependency on a non-JVM runtime.
- **Decision:** Approach **(3)** — JSON SoT plus a Kotlin generator.
  - **Source of truth:** `core/core-designsystem/tokens/tokens.json`, hand-authored, [W3C Design Tokens CG format](https://design-tokens.github.io/community-group/format/). Light/dark token groups present from day one; `light` empty for v1 (dark-mode-only is the §11 sub-decision tracked separately).
  - **Generator:** a Gradle task `:core:core-designsystem:generateTokens` defined in a `build-logic` convention plugin. Reads `tokens.json`, emits:
    - Kotlin: `FluxItColors.kt`, `FluxItTypography.kt`, `FluxItShapes.kt`, `FluxItSpacing.kt`, `FluxItElevation.kt` into `core-designsystem/build/generated/source/tokens/` (registered as a Kotlin source set; gitignored).
    - Swift: `FluxItTokens.swift` into `ios-app/Generated/` (gitignored; xcodegen `project.yml` globs `Generated/**.swift` so the file is picked up on the next `scripts/build-ios.sh`).
  - **Wiring:**
    - Compose side — `generateTokens` is wired as a `dependsOn` of `compileKotlin`.
    - iOS side — `scripts/build-ios.sh` runs `:core:core-designsystem:generateTokens` before invoking `xcodegen` + `xcodebuild`. (Future option: add an Xcode "Run Script" build phase that calls the same Gradle task, evaluated in Phase 15.)
  - **CI guard:** a `verifyTokensInSync` task re-runs the generator and fails the build if the working tree is dirty afterward. Catches the "edited generated file by hand" case.
  - **Status flip:** this ADR stays **Proposed** until the generator lands and produces a clean Compose+SwiftUI round-trip with the verifier green; a follow-up commit on `phase/02-design-system` flips it to **Accepted**.
- **Consequences:**
  - ➕ Zero new runtimes in CI. Generator runs inside the Gradle process every build already does.
  - ➕ The generator is a Kotlin program in the same repo, reviewed under the same Konsist/detekt rules — auditable, not a vendored binary.
  - ➕ `tokens.json` is the only file designers (or future you) edit to change a color across both platforms.
  - ➕ Adding a token = JSON edit + regen. No "did we add it on both sides?" review burden.
  - ➖ ~200 lines of generator code we own. If the W3C DTCG spec evolves materially (currently Editor's Draft), we maintain.
  - ➖ Swift output lives outside Gradle's standard `build/` tree (it lands under `ios-app/Generated/`). Mitigated by gitignore + the `verifyTokensInSync` guard.
  - 🔁 If we later add platform targets (e.g. web, watchOS) the generator gains one more emitter — additive, not a rewrite.
  - 🔁 If the generator ever exceeds ~500 LOC or we need richer transforms (calc(), references, math), revisit by superseding ADR with a Style Dictionary migration plan.
- **Alternatives considered:**
  - **Style Dictionary** (option 1) — rejected for v1: drags Node into a JVM-only CI, and our token surface is small enough that a hand-rolled generator is cheaper to own than the dependency. Re-evaluate at the threshold above.
  - **Hand-maintained-twice** (option 2) — rejected: ADR-001 means every screen consumes tokens on two platforms; drift is the failure mode we most need to design out.
  - **Kotlin-as-SoT** (e.g. an `object FluxItTokens` Kotlin file, then generate Swift from it via KSP or reflection) — rejected: locks designers into editing Kotlin to change a hex value, and KSP-to-Swift generation is not a paved path.
  - **YAML SoT** instead of JSON — equivalent in expressiveness; rejected because the W3C DTCG format is JSON-native and design tools (Figma Tokens plugin, etc.) already export to that JSON shape, keeping a future import path open.
- **Open sub-decisions (deferred to their own ADR entries this phase):**
  - **ADR-005a** — iconography source (vectorized 25-icon set vs. Material Symbols Variable font). _Drafted 2026-05-18, see below._
  - **ADR-005b** — dark-mode-only for v1; reserve namespace for light tokens.

---

### ADR-005a — Iconography: vectorized in-repo SVG set with Compose `ImageVector.Builder` + iOS asset-catalog emitters
- **Status:** Accepted (flipped from Proposed on 2026-05-19 after first clean Compose + SwiftUI round-trip — `assembleDebug` green on Android, `scripts/build-ios.sh` green on iOS, all 29 generator unit tests passing)
- **Date:** 2026-05-18 (Proposed); 2026-05-19 (Accepted; Decision section amended)
- **Context:** Phase 02 §4 owes a concrete iconography pipeline. The screen audit identifies ~25 icons actually used across the v1 surfaces (cart, home, briefcase, plane, fork-knife, dumbbell, star, more, trash, chevron, search, plus, check, bell, camera, settings, account, calendar, list, arrow-up, plus filled-state variants for the four tab icons). Two structural choices need ratifying:
  1. **Source format.** Ship Google's Material Symbols Variable font (~3MB) as a single asset and reference glyphs by codepoint, OR vectorize the ~25 icons as in-repo SVGs and generate platform-native vector primitives. The "vectorize" direction was already recorded verbally in Phase 02 §2's "Resolved decisions" block (2026-05-11); this ADR formalizes it and locks the generator shape.
  2. **Per-platform emission strategy.** Once SVGs are the source, three sub-choices: where do SVGs live, how does the Compose emitter surface them, and how does the iOS emitter surface them. The token pipeline (ADR-005) sets a precedent — JSON source, in-process Kotlin generator, two emitters writing platform-idiomatic output — and the iconography pipeline should mirror that shape rather than invent a parallel toolchain.
- **Decision:**
  - **Source format:** vectorize the ~25 icons we actually ship. SVGs are hand-curated (or exported from Figma) and checked into the repo. New icons = add SVG + regen; no font-codepoint indirection, no ~3MB ride-along binary.
  - **SVG location:** `core/core-designsystem/icons/*.svg`, one file per icon. Sibling to `core/core-designsystem/tokens/tokens.json`. Mirrors the token-pipeline layout so future contributors find both inputs in the same place. Filled-state variants are sibling files with a `-filled` suffix (e.g. `lists.svg` + `lists-filled.svg`).
  - **Generator:** a new Gradle task `:core:core-designsystem:generateIcons`, defined in `build-logic` alongside the existing token generator. Reuses the convention-plugin pattern from `fluxit.designsystem.tokens`. Inputs: every file under `core/core-designsystem/icons/*.svg`. Two emitters:
    - **Compose emitter:** parses each SVG's `<path d="…">` data and emits a single Kotlin file `FluxItIcons.kt` into `core-designsystem/build/generated/source/icons/androidMain/` (settled on androidMain — `commonMain` deferred until a Compose Multiplatform target ships). Each icon becomes a `val FluxItIcons.Cart: ImageVector` getter with a top-level `private var _cart: ImageVector? = null` memoization backing field (mirroring the official Material Icons codegen pattern). The emitter constructs `ImageVector.Builder` **directly** rather than the `materialIcon` helper, because `materialIcon` hardcodes the viewport to 24×24 — Material Symbols ship on a 960×960 grid, and dropping one level keeps the original coordinate space intact (24dp default render size + 960×960 viewport). Absolute Y coordinates are shifted by `-viewBoxMinY` so the Compose viewport anchors at (0,0); relative deltas pass through unshifted. Path commands translate 1:1 to `moveTo`/`lineTo`/`horizontalLineTo`/`verticalLineTo`/`curveTo`/`reflectiveCurveTo`/`quadTo`/`reflectiveQuadTo`/`close` (plus `*Relative` variants) on the path builder. Pure-Kotlin output, no AGP vector-drawable compile step.
    - **iOS emitter:** generates an asset-catalog bundle at `ios-app/Resources/FluxItIcons.xcassets/`, with one `ic-<name>.imageset/` per icon containing `Contents.json` (`template-rendering-intent: template` + `preserves-vector-representation: true`) and a copy of the source SVG renamed to `ic-<name>.svg`. The `ic-` prefix on imageset names avoids collisions with future non-icon image assets. Same emitter also writes a **sibling** Swift accessor file `FluxItIcons.swift` at `ios-app/Generated/icons/` — a `public extension FluxItTokens { enum Icons { … } }` rather than mutating the existing `FluxItTokens.swift` (settled: independent emitters at output time, joined via Swift `extension`). Accessors are of the form `static let cart: Image = Image("ic-cart")`. SwiftUI loads SVGs natively from xcassets since iOS 13; tinting goes through `.foregroundStyle()` as for any monochrome `Image`.
  - **Wiring:**
    - Compose side — `:core:core-designsystem:generateIcons` joins `generateTokens` as a `dependsOn` of every `compile*Kotlin*` task. Both tasks run before Kotlin compile; output dirs are siblings under `build/generated/source/`.
    - iOS side — `scripts/build-ios.sh` invokes `generateIcons` alongside `generateTokens`, before xcodegen. `ios-app/project.yml` already includes `Resources` recursively, so the generated xcassets bundle is picked up without any project.yml edit; one separate `project.yml` change clears `ASSETCATALOG_COMPILER_APPICON_NAME` so `actool` doesn't fail looking for an `AppIcon.appiconset` (a real AppIcon ships in Phase 17 release hardening). The xcassets bundle is gitignored at `ios-app/Resources/FluxItIcons.xcassets/`; the sibling Swift accessor lands under `ios-app/Generated/icons/` (covered by the existing `ios-app/Generated/` gitignore rule).
  - **CI guard:** a separate `verifyIconsInSync` task (parallel to `verifyTokensInSync`) re-runs `generateIcons` and asserts every expected output is present and non-trivial: one Kotlin file (`FluxItIcons.kt`), one Swift accessor file (`FluxItIcons.swift`), one xcassets root `Contents.json`, and one `<name>.imageset/{Contents.json + .svg}` per source SVG. Catches "added an SVG but forgot to commit / regenerate" and silent emitter regressions.
- **Consequences:**
  - ➕ No ~3MB font ride-along; binary size cost is exactly the ~25 icons we use.
  - ➕ One source format (SVG) for both platforms; no font/codepoint indirection drift between Android and iOS.
  - ➕ Pure-Kotlin Compose output keeps the door open for a future Compose Multiplatform target (ADR-001's v2 note) without an AGP-resource detour.
  - ➕ Asset-catalog imagesets are the conventional iOS approach — SwiftUI `Image("ic-cart")` works in previews, supports `.symbolRenderingMode`-ish tinting via `.foregroundStyle()`, and is what Xcode tooling expects.
  - ➕ Generator structure mirrors ADR-005 (same plugin, same `verify*InSync` pattern), so the second pipeline costs less to learn than the first did.
  - ➖ The SVG-path-to-`materialIcon` translator is ~150 LOC of new code we own. SVG path data is a small grammar (`M/L/C/Q/Z` plus relative variants) but corner cases (arcs `A`, transforms on `<g>`, `fill-rule`) need explicit handling or explicit rejection-at-parse.
  - ➖ The translator constrains the SVG dialect: single `<path>` per icon, no transforms, no gradients, no multi-color. Mitigated by curating the source set; flagged at parse time with a clear error if violated.
  - ➖ Adding an icon is a two-step ritual (drop SVG, run `generateIcons` — or rely on `compileKotlin`'s `dependsOn`). Same cost shape as the token pipeline; not a new burden.
  - 🔁 If we ever ship more than ~50 icons, revisit by superseding with a Material Symbols Variable font ADR — the binary-size argument flips around the 1–2MB mark depending on filled-variant count.
  - 🔁 If a future icon needs multi-color or gradient (e.g. branded illustrations), it's out of scope for this pipeline. Such cases route through a separate "illustrations" channel — TBD when the first one appears, not pre-engineered now.
- **Alternatives considered:**
  - **Material Symbols Variable font** — rejected: ~3MB binary cost for 25 glyphs is a 100× overshoot, and codepoint-by-glyph-name indirection ("\\ue8b8" for `home`) is the exact stringly-typed brittleness Konsist is meant to prevent. Re-evaluate at the ~50-icon threshold.
  - **SF Symbols on iOS** (paired with vectorized Compose on Android) — rejected: glyph-to-glyph metrics don't match between SF Symbols and Material/custom SVGs, so a "cart" icon would visibly differ in weight and proportion between Android and iOS. ADR-001's native-feel argument cuts the other way here — visual parity beats per-platform native-ness for a custom icon set.
  - **Compose emitter via `R.drawable.ic_cart` + `ImageVector.vectorResource(...)`** (copy SVG into `androidMain/res/drawable/` per icon, emit a thin getter facade) — rejected: ties the Compose API to AGP's vector-drawable compile step, less portable to a future CMP target, and trades ~150 LOC of translator code for an AGP-coupling we'd then need to remove. Net-neutral on size, net-negative on portability.
  - **Inline pure-Swift `Shape` per icon on iOS** (mirroring the Compose `materialIcon` approach exactly, path data baked into a `struct CartShape: Shape`) — rejected: SwiftUI `Shape`-as-`Image` is unconventional, tinting routes through `.fill()` rather than `.foregroundStyle()`, and previews/asset-catalog tooling don't recognize the shapes. Asset-catalog imagesets are the idiomatic iOS path.
  - **Co-locate SVGs at `design/icons/` (repo root)** — rejected: treats the SVGs as design artifacts external to the module that consumes them, which means the `generateIcons` task references an out-of-module path and Konsist module-boundary rules get noisier. Keeping inputs and outputs both inside `core/core-designsystem/` is cleaner.
- **Resolves Open Questions in Phase 02:** §4 row 1 (font vs. vectorize) and the three generator-shape questions surfaced when picking up §4 (2026-05-18).

---

### ADR-005b — Dark-mode-only for v1; reserve `light`/`dark` token namespace for a future light theme
- **Status:** Accepted (flipped from Proposed on 2026-05-19 after Compose `FluxItTheme` wired and SwiftUI `.preferredColorScheme(.dark)` locked at app root — `assembleDebug` green on Android, `scripts/build-ios.sh` green on iOS)
- **Date:** 2026-05-19
- **Context:** `DESIGN.md` is authored dark-mode-first — every mockup, swatch, and elevation rule in `/design` assumes a dark background, and the brand voice ("calming, sophisticated, dependable") is expressed through deep neutrals + a single accent blue (`#2b7cee`). ADR-005 already noted this as a deferred sub-decision: the token JSON is structured with `light`/`dark` groups, but only `dark` is populated. Phase 02 §6 now needs the policy ratified before the theme wrapper is wired:
  - **Compose:** does `FluxItTheme` provide `darkColorScheme(…)` unconditionally, or does it branch on `isSystemInDarkTheme()` and fall back to a (currently empty) light palette? An unconditional dark theme removes a whole branch of the composition tree and one `CompositionLocal` failure mode.
  - **SwiftUI:** does the app root pin `preferredColorScheme(.dark)`, or does it respect the system setting and risk SwiftUI's automatic Dynamic Color resolution lightening surfaces that were authored for dark contrast?
  - **Token generator:** does `tokens.json` need a populated `light` group for v1 (even if synthetic / placeholder), or can the `light` group stay structurally present but empty, with the generator emitting only the `dark` group?
  Phase 02 §2's "Resolved decisions" (2026-05-11) already lists "✅ Light theme: v1 is dark-only" — this ADR formalizes that line so the implementation rows in §6 reference an accepted ADR rather than a phase-file aside.
- **Decision:** v1 is **dark-only**. No light theme, no system-setting following.
  - **Compose side:** `FluxItTheme` provides a fixed `darkColorScheme(…)` plus the generated token objects (`FluxItColors`, `FluxItTypography`, `FluxItShapes`, `FluxItSpacing`, `FluxItElevation`) via `CompositionLocal`. No `isSystemInDarkTheme()` branch; no light `ColorScheme` constructed.
  - **SwiftUI side:** the app root applies `.preferredColorScheme(.dark)` on the top-level view (in `FluxItApp.swift` or wherever `@main` lives). The generated `FluxItTokens` namespace is already platform-agnostic and stays single-valued.
  - **Token JSON shape:** `tokens.json` keeps the `light`/`dark` group structure from ADR-005, with `light` left empty (`{}`) for v1. The generator emits only the populated `dark` group and ignores empty groups silently. This reserves the namespace so v2 light-theme is additive — populating `light` + adding a single branch — rather than a JSON-schema migration.
  - **Status flip:** ratified on the same commit as the wiring. Compose `FluxItTheme` Composable landed at `core/core-designsystem/src/androidMain/kotlin/dev/franzueto/fluxit/core/designsystem/theme/FluxItTheme.kt` (provides 5 `staticCompositionLocalOf`-backed `Local*` CompositionLocals — colors / typography / shapes / spacing / elevation — wrapping a `MaterialTheme` with a fixed `darkColorScheme(…)` mapped from `FluxItColors` and a `Typography` mapped from `FluxItTypography`); SwiftUI `.preferredColorScheme(.dark)` applied to the `ContentView` inside `WindowGroup` at `ios-app/Sources/FluxItApp.swift`. Mirrors the ADR-005 / ADR-005a accept pattern.
- **Consequences:**
  - ➕ One theme path through both UI stacks. No `isSystemInDarkTheme()` branching in Compose, no Dynamic Color surprises in SwiftUI.
  - ➕ One set of token values to author, review, and screenshot-test. Phase 14's snapshot harness (Paparazzi / swift-snapshot-testing) captures one golden per primitive per platform, not two.
  - ➕ Brand consistency: every screenshot, store-listing asset, and onboarding capture is dark-themed by construction — no risk of a light-mode review build leaking screenshots that don't match marketing materials.
  - ➕ `tokens.json` namespace stays forward-compatible: v2 light-theme is purely additive (populate the `light` group, add one branch to `FluxItTheme`, surface the SwiftUI accessor) — no migration of token IDs.
  - ➖ Users who run their device in light mode get a dark app regardless. For a portfolio app demonstrating a deliberate brand voice this is acceptable; for a productivity tool with broad audience expectations it would not be.
  - ➖ Reviewers on the Play / App Store may flag "doesn't respect system theme" — mitigation: a single line in the store listing's description ("FluxIt is dark by design — light theme coming in a future update").
  - 🔁 v2 light theme is **additive, not a rewrite**: populate the `light` group in `tokens.json`, regenerate, add an `isSystemInDarkTheme()` branch (or a user-facing toggle) to `FluxItTheme`, and surface the corresponding SwiftUI accessor. No call sites of the token namespace need to change.
- **Alternatives considered:**
  - **System-following (both themes at v1)** — rejected: doubles the token-authoring surface, doubles snapshot-test goldens, and requires both palettes to meet WCAG AA against their own surfaces (Phase 02 §8). Phase 02 has not budgeted a light-theme pass and `DESIGN.md` doesn't specify one. Cost is real; v1 value is marginal for a portfolio launch.
  - **Light-only** — rejected immediately: every mockup in `/design` is dark; reversing the brand at v1 would invalidate the entire visual design.
  - **Both at v1, dark as default** — rejected: same cost as system-following without the "respect the user" benefit. Worst of both worlds.
  - **Drop the `light` group from `tokens.json` entirely until v2** — rejected: the empty-group structure is ~zero bytes and reserves the namespace; removing it now means a JSON-schema change later. Cheap to keep, expensive to add back.
- **Resolves Open Questions in Phase 02:** §6 row 1 (dark-only policy) and the deferred sub-decision flagged in ADR-005's "Open sub-decisions" section. §11 row 3 (ADR-005b) is now drafted.

---

### ADR-006 — SQLDelight schema versioning + migration policy
- **Status:** Proposed (flips to Accepted once Phase 03 §2's four `.sq` files land green, `schema.sql` is checked in, `verifySchemaInSync` is wired, and the migration test harness from §10 runs with zero migrations registered)
- **Date:** 2026-05-19
- **Context:** Phase 03 §2 is about to land the v1 schema (four tables: `list`, `item`, `reminder`, `photo`). Before the schema lands, the project needs a ratified answer to: how is the database versioned, what is the legal way to evolve it once a build ships, and how do CI gates catch accidental drift? Three concrete questions surface from §2 / §10:
  1. **Version-number scheme.** SQLDelight 2's migration model is file-based: `<N>.sqm` files named by the version they migrate *to*, `databaseVersion = N` in Gradle bumped per migration. v1 ships as `databaseVersion = 1` with zero `.sqm` files (the `Schema.create(driver)` path runs the current `.sq` files' `CREATE TABLE` statements). The decision is what the bump cadence and naming discipline look like *after* v1 — and whether to allow squashing during the pre-launch portfolio phase.
  2. **Schema-drift detection.** §2 row 4 says `./gradlew :shared:data:generateMainFluxItDatabaseSchema` produces a `schema.sql` dump that is checked in; §2 row 5 says CI fails if the dump is out of sync. Implementation question: do we wrap that in a `verifySchemaInSync` Gradle task that mirrors Phase 02's `verifyTokensInSync` / `verifyIconsInSync` pattern, or just run the diff in a CI shell step?
  3. **Pre-launch vs. post-launch.** This is a portfolio app (ADR-012) heading to Play / App Store. Before the first public release, only the developer is running the app — destructive schema rewrites cost nothing (`adb uninstall` clears local DB). After the first public release, any schema change must preserve user data. The policy needs to make that line explicit so future-me doesn't accidentally write a "just nuke the table" migration into a shipped app.
- **Decision:**
  - **Version scheme:** `databaseVersion` is a monotonic non-negative integer, declared in Gradle inside `sqldelight { databases { create("FluxItDatabase") { … } } }`. v1 ships at version `1`. Every subsequent change to any `.sq` table definition that alters the on-disk shape (add/drop/rename column, add/drop table, change type, add/drop index) requires (a) bumping the integer by exactly 1 and (b) adding the corresponding `<N>.sqm` file under `shared/data/src/commonMain/sqldelight/<package>/migrations/`. Index-only changes still require a migration entry — SQLite indexes are part of the on-disk schema and a missing one is a silent perf regression.
  - **Migration file format:** `<N>.sqm` contains the SQL to migrate from version `N-1` to version `N`. SQLDelight runs them in order on app start when it detects a version delta. Files are named with the integer alone (e.g. `2.sqm`, `3.sqm`); no descriptive suffixes — the `.sqm` is the contract, the human-readable description lives in the commit message and in a leading SQL comment inside the file (mandatory: every `.sqm` opens with `-- <N>.sqm — <one-line summary> (<YYYY-MM-DD>)`).
  - **Forward-only:** migrations are one-directional. No "downgrade" path. If a migration ships broken, the fix is a *new* migration at version `N+1` that corrects the data — never an edit to the shipped `<N>.sqm`. Mirrors the "never edit accepted ADRs in place" rule for the same reason: an immutable on-disk artifact in the wild defines reality.
  - **Pre-launch grace period:** until the first **public** release lands on the Play internal track or TestFlight (whichever fires first — tracked as "first published build" in a `RELEASES.md` entry to be added in Phase 17), `databaseVersion = 1` may be rewritten freely. Changing column types, dropping tables, restructuring the schema — all allowed, with the only cost being a `adb uninstall` / iOS simulator reset between builds. Once the first published build exists, version 1 is frozen and every change goes through the migration discipline above.
  - **Schema dump as authoritative artifact:** `shared/data/schema.sql` is the canonical on-disk schema, generated by `:shared:data:generateMainFluxItDatabaseSchema` (SQLDelight's built-in task). The file is **checked in**, not gitignored. Reasoning: a PR that changes a `.sq` file but not `schema.sql` is a smell — either the author forgot to regenerate (mistake) or the gradle task didn't pick up the change (bug). Both are worth catching at review time, not at runtime.
  - **CI guard task `verifySchemaInSync`:** new Gradle task in the data module that (a) regenerates the schema dump into a temp location, (b) `diff`s against the committed `schema.sql`, (c) fails the build if they differ. Mirrors Phase 02's `verifyTokensInSync` / `verifyIconsInSync` pattern so the same `verify*` family of tasks gates all generated-but-committed artifacts. Wired into `:shared:data:check` so every CI run + every local `gradlew check` catches drift.
  - **Migration test harness from day one:** even with zero migration files at v1, the test harness from §10 registers a `MigrationTest` that walks every `<N>.sqm` from version 1 forward against the current schema using SQLDelight's `Schema.migrate(...)` API. Empty today, mechanical to extend in v2 — adding migration `2.sqm` means adding one test method, not a harness setup.
  - **Data-preservation policy for post-launch migrations:** every migration that drops a column, narrows a column type, or restructures a table must use the `CREATE TABLE temp ... INSERT INTO temp SELECT ... DROP TABLE old; ALTER TABLE temp RENAME ...` pattern that preserves data. Pure additive changes (`ADD COLUMN`, `CREATE INDEX`) need no data-preservation work. Convention enforced by reviewer discipline; if a future ADR makes it tractable we can add a Konsist rule that scans `.sqm` files for `DROP COLUMN` outside the temp-table pattern.
  - **Status flip:** stays Proposed until §2's first commit lands the four real tables, deletes `_Placeholder.sq`, produces a valid `shared/data/schema.sql`, wires `verifySchemaInSync` into `:shared:data:check`, and the §10 migration test harness compiles with zero registered migrations. Mirrors the ADR-005 / 005a / 005b accept pattern.
- **Consequences:**
  - ➕ Schema changes get caught at PR-review time via the `schema.sql` diff, not at runtime on a user's device.
  - ➕ The `verify*InSync` family becomes a consistent pattern across the codebase (tokens, icons, schema) — reviewers know what a `verify*` failure means without per-task context-switching.
  - ➕ Pre-launch grace period removes a class of bureaucratic friction during the v1 build-out — no "I have to write a real migration just to rename a column the user has never seen" moments.
  - ➕ Forward-only migrations + immutable `.sqm` files keep the upgrade path linear and replayable. "What state is this user's DB in?" reduces to "what's their last-applied version number?" with no branching paths.
  - ➕ Mandatory leading comment in every `.sqm` file makes `git log shared/data/src/commonMain/sqldelight/.../migrations/` self-documenting.
  - ➖ Two artifacts (the `.sq` table definition + the `.sqm` migration) describe the same end state from version 2 onward; out-of-sync editing is possible. Mitigated by the `verifySchemaInSync` CI guard — if the migration didn't actually produce the schema the `.sq` files describe, the dump won't match.
  - ➖ The "first published build" boundary needs concrete tracking; until Phase 17 ships `RELEASES.md` we'll rely on memory + this ADR's text. Acceptable for a sole-developer project at this stage.
  - ➖ Pre-launch flexibility is an honor-system rule (no compiler enforces it). Mitigation: once Phase 17's release process exists, the first `git tag v0.x.x` of a publicly distributed build flips the freeze.
  - 🔁 If we ever need to ship a multi-platform binary that supports rolling back to an older version of the app (e.g. for an enterprise flavor with feature flags), revisit by superseding with a forward+backward migration ADR. Not anticipated for v1 or v2.
  - 🔁 If schema authoring ever gets complex enough that hand-writing `.sqm` files becomes error-prone (e.g. dozens of tables, frequent restructuring), revisit with a tooling ADR (codegen migration scaffolds, Atlas, Liquibase). Premature for a 4-table v1.
- **Alternatives considered:**
  - **No version policy; just edit `.sq` files and bump `databaseVersion` by feel** — rejected: this is exactly the dial that, once a build ships, becomes irreversible if you get it wrong. Costs nothing to write the rules down now; costs months to recover from a botched migration in the wild.
  - **Allow editing shipped migrations** — rejected: an immutable `.sqm` is the only way to reason about "what runs on a given user's device." Editing a shipped migration means two users running the same `databaseVersion = 5` can have different schemas depending on when they upgraded. The class of bug this introduces (silent schema divergence) is exactly what migration discipline is for.
  - **Skip the `schema.sql` dump; rely on migration tests alone** — rejected: tests catch behavior, the dump catches *intent*. A reviewer scanning a PR diff with a 30-line `schema.sql` change immediately sees the shape of the schema move; the same change buried in a `.sqm` is harder to read at a glance. Costs ~zero LOC to keep.
  - **Squash + restart at every release (no real migrations)** — rejected: user data loss between every app update. Even acceptable for a portfolio app pre-launch, this fails on the first real release.
  - **Annotation-driven autoMigration (Room-style)** — rejected: SQLDelight doesn't ship this, and adding a codegen layer on top of SQLDelight's already-codegen pipeline is a non-trivial second toolchain to own. Re-evaluate at the threshold above.
  - **Inline migration commentary inside `.sq` table files (no separate `.sqm`)** — not an option; SQLDelight 2's plugin requires `.sqm` for migrations. Listed here only to record that the file-separation isn't our choice.
- **Resolves Open Questions in Phase 03:** §2 rows 4 & 5 (schema dump + CI guard mechanism); the question of pre-launch vs. post-launch schema flexibility raised when picking up §2 (2026-05-19). Does **not** resolve §12 row 1–5 (those are product-shape questions, not schema-versioning questions).
- **Open sub-decisions (deferred to their own ADR entries this phase):**
  - **ADR-006a** — TEXT primary keys with UUID v4 (vs. ULID, vs. INTEGER autoincrement).
  - **ADR-006b** — Soft-delete + tombstones from day one.
  - **ADR-006c** — Coupling `:shared:data` to `:core:core-designsystem` for the `IconNameAdapter` enum.

---

### ADR-006a — TEXT primary keys with UUID v4 string ids
- **Status:** Proposed (flips to Accepted once Phase 03 §9 lands `expect fun newId(): String` with `java.util.UUID.randomUUID()` on Android and `NSUUID().UUIDString.lowercased()` on iOS, plus the Konsist rule forbidding `Random` / `currentTimeMillis()` as id sources)
- **Date:** 2026-05-19
- **Context:** Phase 03 §2's table header line ("All tables use **TEXT primary keys** … so future sync doesn't require an integer-id remap") already commits to TEXT PKs; this ADR ratifies *which* string id format and locks it. Three candidates were on the table:
  1. **`INTEGER PRIMARY KEY AUTOINCREMENT`.** SQLite's native fast path. ROWID-aliased, sequential, ~8 bytes per id, zero generation cost. Lethal at the v2 sync boundary: two devices creating rows offline both assign id=1 to different lists; reconciliation requires a global remap that touches every foreign key in the database (`item.list_id`, `item.photo_id`, `reminder.owner_id`). ADR-003 already commits to v1 contracts that "v2 sync can swap implementations without touching call sites" — autoincrement breaks that promise at the schema level, not just the call-site level.
  2. **UUID v4.** Random 128-bit ids, globally unique with collision probability ~2^-122 (effectively zero). Cross-platform mint with zero dependencies: `java.util.UUID.randomUUID()` on Android (JVM standard library), `NSUUID().UUIDString` on iOS (Foundation standard library). 36-byte string form (`550e8400-e29b-41d4-a716-446655440000`). Not time-sortable — but every FluxIt table has a `created_at INTEGER` column anyway, so chronological queries don't need PK ordering.
  3. **ULID.** 128-bit, Crockford-base32-encoded (26 chars), lexicographically time-sortable. The clean fix to UUID v4's "PK not sortable" complaint. Two real costs: (a) no platform-stdlib generator — needs a ~50-line hand-roll or a small dep like `com.aventrix.jnanoid` ported, (b) the encoded timestamp leaks creation time to anyone who can read a list/item id, which matters slightly more for an app where lists may eventually be shared (v2). Time-sortability is also a partial win: a sorted index on `(deleted_at, created_at, id)` gives the same query plan today without the ULID encode/decode.
- **Decision:** **UUID v4 strings**, lowercase, hyphenated, no braces, no `urn:uuid:` prefix.
  - **On-disk shape:** SQLite `TEXT` column, 36-byte canonical form (e.g. `550e8400-e29b-41d4-a716-446655440000`). One representation everywhere — no `BLOB`-encoded compact form, no upper-case variant, no braces. Anything that round-trips through the DB matches `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`.
  - **Generation surface:** a single `expect fun newId(): String` declared in `:core:core-utils` (commonMain), with `actual` per platform:
    - **Android (JVM):** `java.util.UUID.randomUUID().toString()`. JVM `SecureRandom` underneath.
    - **iOS:** `platform.Foundation.NSUUID().UUIDString.lowercased()`. Apple-provided CSPRNG underneath.
    - **Tests:** an `internal val idGenerator: () -> String = ::newId` indirection where tests need deterministic ids; tests pass a `FakeIdGenerator` returning `"00000000-0000-4000-8000-000000000001"` etc. (still UUID-v4-shaped so regexes in production code don't trip).
  - **`newId()` lives in `:core:core-utils`, not `:shared:data`.** Reason: ids are minted at the *use-case* layer (in `:shared:domain` repositories' `create(...)` impls *call into* the data layer, but the id itself is generated above the DB so that retry/replay scenarios produce stable ids). Putting `newId()` in core-utils makes it available to both `domain` and `data` without cycles. (Phase 04 will surface a `ListId` / `ItemId` / etc. value class layer on top; those are inline TEXT-backed value classes — they don't change the underlying storage decision.)
  - **Banned id sources:** `kotlin.random.Random`, `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()`, `System.currentTimeMillis()`, hash-of-content schemes (e.g. `name.hashCode().toString()`). Enforced by a Konsist rule in `:build-logic`'s arch test set: any file (outside test source sets and outside the actual implementation of `newId()` itself) that assigns to a property named `*id` from those sources is a violation. This protects against the "I'll just use a counter for now" pattern that creates duplicate-id bugs in the v2 sync window.
  - **No `created_at`-based id ordering.** Lists are sorted by `sort_order REAL` (fractional indexing — §8); items inherit that. The dashboard's chronological-newest-first feel (§12 row 5 — still an open question) comes from `created_at DESC`, not PK order. The PK is opaque to product code.
  - **Status flip:** stays Proposed until §9's commit lands `expect fun newId(): String` in `:core:core-utils` with both actuals + the Konsist banned-source rule. Pattern matches ADR-006's status-flip discipline.
- **Consequences:**
  - ➕ Cross-device sync (v2) needs no id remap. A list created offline on phone A and one created offline on phone B collide with probability effectively zero; merge is a simple union.
  - ➕ Zero new dependencies. Both platforms' standard libraries cover generation; the only cross-platform code is the `expect fun newId()` declaration.
  - ➕ Deterministic-test path is trivial — `FakeIdGenerator` returns shaped ids and is injected wherever ids are minted. No DB-fake gymnastics.
  - ➕ 36-byte canonical form is human-readable in SQLite browser tools, in Kermit logs, and in stack traces. Aids debugging vs. opaque blobs.
  - ➕ Stable Konsist rule against "I'll use a counter" id sources means the v1→v2 sync transition isn't blocked by a stowaway integer-id field that snuck into some draft type.
  - ➖ 36 bytes per id × 2 ids per `item` row (own + `list_id`) + 2 more (`photo_id`, FK targets) = ~108–144 bytes of id overhead per item row. At realistic scales (thousands of items per user) this is negligible; at millions it would matter. Premature to optimize.
  - ➖ TEXT PK indexes are larger and slightly slower than INTEGER ROWID. Mitigated by SQLite's b-tree handling 36-byte keys efficiently and by the small data volumes a personal list app sees.
  - ➖ Not time-sortable by PK. Acceptable because every table has `created_at INTEGER` and the partial indexes from §2 (`list_sort_idx`, `item_list_idx`) already cover the queries that matter.
  - ➖ The ban on `Random` / `currentTimeMillis()` as id sources catches *intent* via a Konsist regex, not via the type system. A determined developer could route through a wrapper function. Acceptable: the rule is honest documentation of what good looks like, not a sandbox.
  - 🔁 If the schema ever needs a PK shape that's both globally unique *and* time-sortable (e.g. a v2 audit log where chronological scans dominate), revisit by superseding with a ULID ADR for the specific table. Different tables can use different id shapes — the rule here is the *default*.
  - 🔁 If the codebase ever needs a non-Foundation/non-JVM target (e.g. Compose-Multiplatform-on-Web, watchOS without Foundation), the `expect fun newId()` shape makes adding a `wasmJsMain` / `watchosMain` actual a one-file change.
- **Alternatives considered:**
  - **INTEGER AUTOINCREMENT** (option 1) — rejected: see Context. The "remap on first sync" cost is a project-killer for the v2 milestone that the v1 schema is meant to enable. Doubling down on autoincrement now is paying a small win today and a multi-week debt later.
  - **ULID** (option 3) — rejected: the costs (own a generator, time-leaks the creation timestamp in every visible id) outweigh the wins (PK-sortable; the `created_at` column already serves that role). Re-evaluate per-table if a future feature needs PK-time-order as a primary access pattern.
  - **UUID v7** (time-ordered v7 from RFC 9562) — would resolve UUID v4's only real complaint (not time-sortable). Rejected for v1 because (a) neither platform's standard library ships a v7 generator yet (as of Android 14 / iOS 17), so adoption would mean owning a generator just like ULID, and (b) v7 ids still leak the creation timestamp to anyone reading them. Re-evaluate when both platforms' stdlibs ship native v7 support.
  - **UUID v4 stored as 16-byte `BLOB` instead of 36-byte `TEXT`** — rejected: ~3× space win in id columns is real but small at FluxIt's scale, and `BLOB` ids are unreadable in `sqlite3` shells / SQLite browser / log lines. Debugging cost exceeds storage cost.
  - **Composite ids** (e.g. `<device_uuid>:<local_counter>`) — rejected: solves no problem UUID v4 doesn't already solve, while introducing the parsing/concat surface that pure-random ids skip entirely.
  - **`newId()` lives in `:shared:data`** instead of `:core:core-utils` — rejected: ids are minted *above* the data layer (use-case-level, where retry/replay produces stable ids); putting the generator in `:shared:data` either forces an upward dependency from `domain` or duplicates generation logic. `:core:core-utils` is the existing leaf module designed for this kind of platform-agnostic primitive.
- **Resolves Open Questions in Phase 03:** §9 row 1 (id format) and the implicit "v2-sync-safe?" question that §2's TEXT-PK header line raises. Does **not** resolve §12 (those are product-shape questions).

---

### ADR-006b — Soft-delete + tombstones from day one
- **Status:** Proposed (flips to Accepted once Phase 03 §2's four `.sq` files land with `deleted_at INTEGER` on every table, all read queries carry `WHERE deleted_at IS NULL`, and the application-cascade behavior is exercised by the integration test)
- **Date:** 2026-05-19
- **Context:** Phase 03 §2's table header line already commits to "Soft-deletes via `deleted_at` so v2 sync can replicate tombstones; v1 queries always filter `WHERE deleted_at IS NULL`." This ADR ratifies the *full* policy — column shape, cascade semantics, undo path, photo lifecycle, and the cost trade — before the schema lands so the contract is uniform across all four tables and across both repositories and use cases. Five sub-questions surface:
  1. **Per-table vs. universal.** Does every table soft-delete, or only the ones with v2-sync implications? §2's column inventory already shows `deleted_at INTEGER` on all four (`list`, `item`, `reminder`, `photo`); this ADR ratifies "all four, no exceptions" so future schema additions inherit the rule by convention.
  2. **Foreign-key cascade semantics.** The schema declares `item.list_id REFERENCES list(id) ON DELETE CASCADE` — that's a *hard*-delete cascade at the SQLite engine level, which we never invoke in v1 (no hard deletes outside the photo janitor). When a list soft-deletes, two paths exist for its items: (a) **application cascade** — repository's `softDelete(list)` also soft-deletes every child item in a single transaction; (b) **logical cascade by query** — child items keep `deleted_at IS NULL` but every item read joins to its parent list and filters `parent.deleted_at IS NULL`. Each has consequences (a is simpler reads, harder restore; b is harder reads, trivial restore — but v1 has no restore UI).
  3. **Undo / restoration path.** §12 row 3 (open) asks whether the dashboard's trash icon is "soft delete with no undo" or "soft delete with 5-second toast-undo." Either way the *mechanism* is the same — what changes is the timing of the soft-delete write. This ADR codifies the column shape both flows depend on without resolving the UX choice.
  4. **Photo lifecycle.** §7 specifies a janitor that *hard*-deletes photo files + rows when orphaned for >24h. So photos have a two-stage lifecycle (soft → hard) that lists / items / reminders don't have in v1. Documented here so the asymmetry is intentional, not an oversight.
  5. **When does v1 ever hard-delete?** Only the photo janitor — and only photos. Lists, items, reminders are forever-tombstoned in v1; v2 sync's compaction phase is the future garbage-collector.
- **Decision:**
  - **Universal column shape.** Every table in the FluxIt schema gets `deleted_at INTEGER` (nullable epoch ms, UTC). Convention: `NULL` = live row; non-`NULL` = tombstone, set to the moment the soft-delete was committed (via injected `Clock`, never `System.currentTimeMillis()` directly — see ADR-006a's discipline). Any *new* table added in v2 inherits this convention by default; an exception requires a superseding ADR per-table.
  - **Read-path discipline.** Every SELECT in every `.sq` file ends with `WHERE deleted_at IS NULL` (or with that filter embedded in an inner clause if the query is multi-source). No exceptions in v1. Aggregations (`COUNT`, `SUM`) also filter — a soft-deleted item does not count toward `total_items` / `completed_items` for the dashboard. Enforced by reviewer discipline; not Konsist-rule-able (Konsist can't parse SQL semantics). The `verifySchemaInSync` task (ADR-006) protects the column existence; the per-query test in §10 protects the filter usage (every query test creates a tombstoned row and asserts it's *not* returned).
  - **Write-path: application cascade (option (a) above).** When a list is soft-deleted, the repository's `softDelete(listId)` is a single SQL transaction that:
    1. Sets `deleted_at = :now` on the list row.
    2. Sets `deleted_at = :now` on every `item` where `list_id = :listId AND deleted_at IS NULL`.
    3. Sets `deleted_at = :now` on every `reminder` where `owner_type = 'LIST' AND owner_id = :listId AND deleted_at IS NULL`.
    4. *Does not* touch `photo` rows — photos may be referenced by items in *other* (live) lists; orphan detection is the photo janitor's job (§7).

    When an item is soft-deleted, the cascade is:
    1. Set `deleted_at = :now` on the item.
    2. Set `deleted_at = :now` on every `reminder` where `owner_type = 'ITEM' AND owner_id = :itemId AND deleted_at IS NULL`.

    Reminders and photos have no children — their `softDelete` is a single-row write.

    The cascade runs in a SQLite transaction; if any step fails, the whole soft-delete reverts.
  - **No restoration surface in v1.** Soft-delete is a one-way operation from the user's perspective. The §12 row 3 question (immediate vs. 5-second undo) is a *staging* choice — undo-via-don't-yet-commit, not undo-via-rewind. If a future v2 feature exposes a "trash" view, the restore op will need to repopulate `deleted_at = NULL` on the row *and* its children, but the cascade discipline above is already symmetric (every parent tombstone implies child tombstones at the *same* `deleted_at` timestamp, so "restore everything that died with this list" is a single query against `deleted_at = :listsDeletedAt`). Designed for restorability without requiring it in v1.
  - **Photo lifecycle is two-stage.** Photos soft-delete (set `deleted_at`) but are *also* the only entity with a hard-delete path in v1: the photo janitor (§7, scheduled by Phase 04's `PhotoJanitor` use case on app start) finds rows where `deleted_at IS NOT NULL AND deleted_at < now - 24h AND no live item references the photo`, then deletes the file via `PhotoStorage.delete()` and hard-deletes the row. The 24-hour grace window absorbs the "user soft-deleted by mistake, item that referenced the photo is still live (rare race), or future undo surface" cases. v2 sync compaction may shorten or lengthen this window per a future ADR.
  - **Tombstone retention guarantees v2-sync correctness.** Because v1 never hard-deletes a list / item / reminder, every tombstone is available on first sync to be replicated cross-device — the v2 sync engine doesn't need to invent "last-seen-by-server" reconciliation; it just merges tombstones the way it merges live rows. The cost (forever-growing tombstone tail) is bounded by user behavior — a personal list app on a single device sees thousands of rows over years, not millions. v2 may add a "tombstones older than X synced everywhere → safe to hard-delete" compactor; out of scope today.
  - **Write amplification is acceptable.** Soft-deleting a list with 100 items is ~101 row UPDATEs in a transaction. SQLite handles this in well under 10ms on phone hardware. Far below human-perceptible thresholds.
  - **Status flip:** stays Proposed until §2's first commit lands all four `.sq` files with `deleted_at INTEGER` columns, every read query has the `WHERE deleted_at IS NULL` filter, the cascade behavior is exercised by the §10 integration test (create list → add 3 items → soft-delete list → assert items and reminders all tombstoned in one transaction → reopen DB → state holds), and the photo janitor's orphan-detection query (`selectOrphaned`) is in place even though the janitor use case ships in Phase 04. Pattern matches ADR-006 / 006a status-flip discipline.
- **Consequences:**
  - ➕ v2 sync engine slots in without a schema migration. The tombstone column already exists, populated correctly, indexed for filtered reads. ADR-003's "v2 sync swaps implementations, doesn't touch schema" promise holds.
  - ➕ Every "delete" is reversible at the data layer even when v1 doesn't expose a reversal UI. Buys optionality for v2 features (trash bin, audit log, sync conflict resolution) at near-zero cost today.
  - ➕ Application-cascade keeps read queries simple — every SELECT is a single-table filter on `deleted_at IS NULL`, no joins to parent tombstone state. Phase 03 §2's index design (`item_list_idx ON item (list_id, is_completed, sort_order) WHERE deleted_at IS NULL`) assumes this — query-cascade would have needed different indexes.
  - ➕ Symmetric cascade timestamps (every child gets the same `deleted_at` as the parent) make v2 restoration tractable as a single equality query rather than a tree walk.
  - ➕ The 24-hour photo janitor window absorbs the "mis-tap delete on the only photo of a thing" case without requiring undo UX scaffolding in v1.
  - ➖ Every read query carries one extra column filter. Negligible perf cost; mitigated by partial indexes (`list_starred_idx ... WHERE deleted_at IS NULL`, `item_list_idx ... WHERE deleted_at IS NULL`, etc.).
  - ➖ Convention is enforceable by reviewer + tests, not by type system. A future query author could forget the filter and ship a bug that returns tombstoned rows. Mitigated by §10's "every query test verifies tombstoned rows are excluded" rule.
  - ➖ Storage tail grows forever in v1. At realistic personal-app scale (a few hundred lists, a few thousand items over years), this is < 100KB of tombstones — irrelevant. At pathological scale (a user importing tens of thousands of items then mass-deleting), the photo janitor handles photos but lists/items/reminders bloat the DB. Acceptable trade-off; v2 compactor addresses it.
  - ➖ Application cascade means the data layer "knows about" the parent-child shape, not just the tables. The `ListsRepository.delete(id)` impl touches three tables in one transaction. Slightly more imperative than a pure CASCADE would be. Mitigated by keeping the cascade logic inside the repository (the only place that touches multiple tables in v1 anyway), not bleeding it into use cases.
  - ➖ The `ON DELETE CASCADE` FK declarations in the schema are dead code in v1 (we never trigger them since we never hard-delete a list). Kept for safety — if v2 sync compaction ever hard-deletes a list, items + reminders get cleaned up at the SQLite engine level too.
  - 🔁 If v2 sync compaction lands and tombstone retention becomes a config knob, revisit by superseding with an ADR on tombstone GC policy.
  - 🔁 If a future feature (e.g. shared lists, multi-user) requires per-row deletion attribution (who deleted, from which device), the column shape extends from `deleted_at INTEGER` to a `deleted_by` TEXT + `deleted_at` pair. Additive; not a v1 concern.
- **Alternatives considered:**
  - **Hard-delete only (no tombstones in v1)** — rejected: the v1→v2 transition then needs a schema migration to add `deleted_at` to every table *and* a backfill strategy for rows that were hard-deleted before sync. That's a worse-than-impossible problem (already-deleted data is gone). Cheap insurance now beats expensive recovery later.
  - **Tombstone only the tables that need v2 sync (e.g. just `list` + `item`)** — rejected: asymmetry creates two read-path conventions in the same codebase. Every reviewer would have to remember "is this a tombstoned table?" before scanning a query. The uniform rule is simpler than the optimization is valuable.
  - **Query-cascade (option (b) above)** — rejected: every item read joins to list and filters `list.deleted_at IS NULL`. Two-table reads everywhere; index design changes (compound partial indexes spanning two tables aren't a SQLite primitive); count queries get hairier. The "restorability without rewriting children" win doesn't apply in v1 (no restore UI), and even in v2 the equality-by-`deleted_at` restoration is straightforward with application cascade.
  - **Status column (`status TEXT` with values `ACTIVE`/`DELETED`)** instead of `deleted_at INTEGER` — rejected: loses the timestamp information that makes the photo janitor (and future tombstone GC) tractable. A TIMESTAMP column gives you the boolean plus useful metadata.
  - **External "deletions" table** that records (entity_type, entity_id, deleted_at) and live tables stay tombstone-free — rejected: every read query then joins to the deletions table, and SQLite query planner has a harder time using partial indexes. The "column on every table" model is the conventional choice for a reason.
- **Resolves Open Questions in Phase 03:** ratifies the §2 header-line tombstone commitment; clarifies the cascade semantics needed by `ListsRepository.delete()` / `ItemsRepository.delete()` in §5. Does **not** resolve §12 row 3 (immediate vs. toast-undo UX) — that's a Phase 07 question, this ADR only fixes the column shape that backs whichever choice lands.

---

## Pending / Anticipated ADRs

These are *expected* to be opened during the relevant phase. Listed here so we don't forget.

- **ADR-006c** (Phase 03): remaining sub-ADR of ADR-006 — `:shared:data` ↔ `:core:core-designsystem` coupling for the `IconNameAdapter` enum.
- **ADR-007** (Phase 05): MVI store contract — intents/state/effects, error model, optimistic update pattern.
- **ADR-008** (Phase 06): expect/actual vs. Koin-injected interfaces for platform capabilities (we'll likely standardize on injected interfaces).
- **ADR-009** (Phase 13): Notification permission UX — when to ask, how to recover from denial.
- **ADR-010** (Phase 14): Test pyramid shape and minimum coverage gates per layer.
- **ADR-011** (Phase 15): Branching strategy + CI matrix shape (trunk-based vs. GitFlow).
