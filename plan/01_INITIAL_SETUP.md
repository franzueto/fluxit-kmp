# Phase 01 — Initial Setup

> **Goal:** Stand up an empty-but-production-grade KMP repository that compiles on Android *and* iOS, with version catalog, convention plugins, lint/format, and a green CI smoke build. No features, no UI screens — just the foundation every later phase depends on.

**Owner:** Mobile platform
**Depends on:** —
**Blocks:** All other phases.
**Exit criteria (Definition of Done):**
- `./gradlew build` green locally and in CI on `ubuntu-latest`.
- `xcodebuild` green on `macos-latest` against the iOS app target.
- `./gradlew detekt ktlintCheck konsistTest spotlessCheck` green.
- One PR opened end-to-end exercises the PR template, CODEOWNERS, status checks, and required reviews.

---

## 1. Repo skeleton

- [x] Create root directory layout exactly as the architecture overview specifies (`/android-app`, `/ios-app`, `/shared/{domain,data,state}`, `/core/*`, `/features/*`, `/platform/*`, `/build-logic`, `/docs`, `/.github`).
- [x] Add `.gitignore` covering: `.gradle/`, `build/`, `local.properties`, `.idea/`, `xcuserdata/`, `Pods/`, `*.xcuserstate`, `.DS_Store`, `kotlin-js-store/`.
- [x] Add `.gitattributes` enforcing LF line endings for `*.kt`, `*.kts`, `*.swift`, `*.yml`, `*.md`.
- [x] Add `.editorconfig` with: `indent_size=4` for Kotlin, `2` for YAML, `max_line_length=140`, `insert_final_newline=true`.
- [x] Add root `README.md` with: one-line product description, prerequisites, "How to run Android", "How to run iOS", links to `MASTER_PLAN.md` and `docs/ARCHITECTURE.md`.
- [x] Add `LICENSE` placeholder (TBD with product owner; default to "All rights reserved" until decided).

## 2. Toolchain pinning

- [x] `gradle/wrapper/gradle-wrapper.properties` pinned to a current 8.x. (8.11.1, distribution-type=all)
- [x] `.tool-versions` (asdf) and/or `mise.toml`: pin JDK 21 (Temurin), Kotlin via Gradle plugin (no separate pin), Ruby 3.x for Fastlane (Phase 15 prep), Xcode version documented in README.
- [x] `gradle.properties`:
  - `org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC`
  - `org.gradle.parallel=true`
  - `org.gradle.caching=true`
  - `org.gradle.configuration-cache=true`
  - `kotlin.code.style=official`
  - `kotlin.mpp.androidSourceSetLayoutVersion=2`
  - `android.useAndroidX=true`
  - `android.nonTransitiveRClass=true`
- [x] Verify: `./gradlew --version` works on a fresh clone.

## 3. Version catalog (`gradle/libs.versions.toml`)

- [x] Define `[versions]` for: `kotlin`, `agp`, `compose-bom`, `androidx-activity`, `androidx-navigation`, `koin`, `sqldelight`, `kotlinx-coroutines`, `kotlinx-serialization`, `kotlinx-datetime`, `kermit`, `skie`, `ktlint`, `detekt`, `spotless`, `konsist`, `turbine`, `kotest`, `mockk`.
- [x] Define `[libraries]` and `[plugins]` aliases for each.
- [x] Define `[bundles]`: `coroutines`, `koin`, `sqldelight-runtime`, `compose-ui`, `testing-shared`.
- [ ] Add a CI job (Phase 15) that runs `./gradlew dependencyUpdates` weekly.   <!-- deferred to Phase 15; plugin alias `dependency-versions` already in catalog -->


## 4. `build-logic` convention plugins

- [x] `build-logic/settings.gradle.kts` with `pluginManagement` pointing to `gradlePluginPortal()`, `google()`, `mavenCentral()`.
- [x] `build-logic/build.gradle.kts` declares the convention plugins.
- [x] Plugin: `fluxit.kmp.library`
  - applies `kotlin-multiplatform`, `android-library`, `kotlinx-serialization`
  - sets JVM target 17, Android compileSdk/minSdk/targetSdk
  - configures `iosX64`, `iosArm64`, `iosSimulatorArm64` with a static framework named after the module
  - applies SKIE (only when `iosMain` is present)   <!-- SKIE applied unconditionally; all KMP libs in v1 have iOS targets -->
- [x] Plugin: `fluxit.kmp.feature`
  - extends `fluxit.kmp.library`, pre-wires Koin, Kermit, kotlinx-datetime, Turbine in test
  - forbids dependencies on other `feature-*` modules (Konsist rule registered)   <!-- Konsist rule deferred to section 8 build-logic test sources (graph-wide invariant) -->
- [x] Plugin: `fluxit.android.application`
  - applies `com.android.application`, Compose, ktlint, detekt (via fluxit.quality)
  - sets `applicationId = "dev.franzueto.fluxit"` (per ADR-012), signing config placeholder, R8 enabled in release
- [x] Plugin: `fluxit.quality`
  - applies ktlint, detekt, Spotless (Kotlin + KTS + Markdown)
  - registers Konsist test source set   <!-- Konsist test source lives in build-logic; not "registered" per-module -->
- [x] Verify: a stub `:core:core-utils` module applying `fluxit.kmp.library` builds.

## 5. Settings & module wiring

- [x] Root `settings.gradle.kts` with `pluginManagement`, `dependencyResolutionManagement` (`repositoriesMode = FAIL_ON_PROJECT_REPOS`), `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`.
- [x] Include all module paths from the architecture overview, even if their `build.gradle.kts` is currently a stub applying `fluxit.kmp.library`. **No `:features:*` modules yet** — phases 07–10 add them.
- [x] Stub `build.gradle.kts` for each `core-*` and `platform-*` module so the graph resolves.   <!-- also covers shared/{domain,data,state} -->


## 6. Android app shell

- [ ] `android-app/build.gradle.kts` applies `fluxit.android.application`, depends on `:shared:state` and `:core:core-designsystem` (both stubs for now).
- [ ] `MainActivity` with empty Compose `Surface` + a `Text("FluxIt")`.
- [ ] `AndroidManifest.xml` with `<application>` `android:name=".FluxItApp"`, `android:theme="@style/Theme.FluxIt"`.
- [ ] `FluxItApp` initializes Koin (empty modules list for now).
- [ ] Verify: `./gradlew :android-app:assembleDebug` produces an APK that launches in an emulator.

## 7. iOS app shell

- [ ] `ios-app/FluxIt.xcodeproj` (created via `xcodegen` from a `project.yml` checked in to repo to keep diffs reviewable).
- [ ] `project.yml` declares one app target `FluxIt` (iOS 16+, SwiftUI lifecycle), embeds the `shared` XCFramework via Gradle task `:shared:state:assembleSharedXCFramework`.
- [ ] `FluxItApp.swift` + empty `ContentView.swift` rendering `Text("FluxIt")`.
- [ ] Add a Gradle task / shell script `scripts/build-ios.sh` that wraps `./gradlew :shared:state:assembleSharedReleaseXCFramework && xcodebuild -project ios-app/FluxIt.xcodeproj -scheme FluxIt -sdk iphonesimulator build`.
- [ ] Verify: script exits 0 on a clean clone (macOS only).

## 8. Quality gates

- [ ] ktlint configured via `fluxit.quality` — run on all Kotlin source sets.
- [ ] detekt with a `config/detekt.yml` checked in (start from `detektGenerateConfig`, then prune to: complexity, naming, exceptions, coroutines rules).
- [ ] Spotless: Kotlin (ktlint), KTS (ktlint), Markdown (prettier).
- [ ] Konsist test in `:build-logic` test sources enforcing:
  - Domain has no Android/iOS imports.
  - `feature-*` modules don't depend on each other.
  - `GlobalScope` and `runBlocking` blocked outside `*Test` source sets.
- [ ] Pre-commit hook (`.githooks/pre-commit` + opt-in install via `scripts/install-hooks.sh`) runs `./gradlew spotlessCheck ktlintCheck` on changed files only.

## 9. Documentation seeds

- [ ] `docs/ARCHITECTURE.md` — copy the architecture overview from `MASTER_PLAN.md` and expand with module-by-module responsibilities (one paragraph each). Include a Mermaid module dependency diagram.
- [ ] `docs/DECISIONS.md` — short pointer to `plan/00_DECISIONS.md` (single source of truth) so external readers find ADRs.
- [ ] `docs/SCALING.md` — placeholder with a TODO list (filled during Phase 17): module ownership matrix, when to split a feature module, version catalog stewardship.
- [ ] `docs/TEAM_GUIDELINES.md` — placeholder: PR etiquette, code review SLAs, branch naming, commit message convention (`type(scope): subject`).

## 10. CI smoke build (`/.github`)

- [ ] `.github/workflows/ci.yml`:
  - Triggers: `pull_request`, `push` to `main`.
  - Job `jvm-build` on `ubuntu-latest`: setup-java 21, setup-gradle (with cache), `./gradlew spotlessCheck ktlintCheck detekt konsistTest assembleDebug`.
  - Job `ios-build` on `macos-latest`: same JDK setup, then `scripts/build-ios.sh`.
  - Concurrency group cancels superseded runs on the same branch.
- [ ] `.github/CODEOWNERS` — assign `*` to mobile platform team placeholder; carve out `/ios-app/` and `/android-app/`.
- [ ] `.github/pull_request_template.md` with: Summary, Linked phase/ADR, Screenshots (mobile), Test plan, Risk.
- [ ] `.github/dependabot.yml` for Gradle + GitHub Actions weekly.
- [ ] Branch protection on `main` (documented in README; enforced via repo settings outside this checklist).

## 11. Sanity tests

- [ ] `:core:core-utils` ships one trivial `Result` extension and a unit test asserting it — proves the test harness runs on JVM.
- [ ] `:shared:state` ships an empty `expect class Platform` with `androidMain` / `iosMain` `actual` returning a string — proves expect/actual + iOS framework export work.
- [ ] iOS app prints `Platform().name` — proves SKIE/framework consumption works end-to-end.

## 12. Hand-off checklist (gate to Phase 02)

- [ ] All checkboxes above are ✅.
- [ ] CI is green on a representative PR (not just `main`).
- [ ] `MASTER_PLAN.md` updated: Phase 01 → 🟢, "▶ Next Step" advanced to Phase 02.
- [ ] No TODOs left in any `build.gradle.kts` without an issue link.

---

## Open questions for this phase

_Surfaced here as we go; resolved questions move to ADRs._

- [ ] **Min Android SDK?** Default proposal: `minSdk = 26` (covers ~95% as of 2026, unblocks adaptive icons + foreground service types for reminders). Confirm.
- [ ] **Min iOS version?** Default proposal: `iOS 16` (SwiftUI `NavigationStack`, `PHPicker` improvements, `Observable` macro available). Confirm.
- [ ] **Package ID base.** Default `com.fluxit` — confirm or override (e.g., reverse-DNS of an owned domain).
- [ ] **Default branch model.** Trunk-based with short-lived feature branches assumed; defer to ADR-011 in Phase 15.
