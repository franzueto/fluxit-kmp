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
- **Status:** Proposed
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
  - **ADR-005a** — iconography source (vectorized 25-icon set vs. Material Symbols Variable font).
  - **ADR-005b** — dark-mode-only for v1; reserve namespace for light tokens.

---

## Pending / Anticipated ADRs

These are *expected* to be opened during the relevant phase. Listed here so we don't forget.

- **ADR-005a** (Phase 02): Iconography source — vectorized in-repo SVG set vs. Material Symbols Variable font.
- **ADR-005b** (Phase 02): Dark-mode-only for v1; reserve token namespace for a future light theme.
- **ADR-006** (Phase 03): SQLDelight schema versioning + migration policy.
- **ADR-007** (Phase 05): MVI store contract — intents/state/effects, error model, optimistic update pattern.
- **ADR-008** (Phase 06): expect/actual vs. Koin-injected interfaces for platform capabilities (we'll likely standardize on injected interfaces).
- **ADR-009** (Phase 13): Notification permission UX — when to ask, how to recover from denial.
- **ADR-010** (Phase 14): Test pyramid shape and minimum coverage gates per layer.
- **ADR-011** (Phase 15): Branching strategy + CI matrix shape (trunk-based vs. GitFlow).
