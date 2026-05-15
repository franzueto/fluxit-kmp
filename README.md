# FluxIt

A high-performance list-making app for Android and iOS, built natively with **Kotlin Multiplatform**. Domain, data, and state logic shared; UI native (Jetpack Compose + SwiftUI). Dark-mode-first, offline-only in v1.

> **Status: Phase 01 (Foundation) in progress.** Android + iOS app shells build green against shared `:shared:state`; all four quality gates (ktlint, detekt, Spotless, Konsist) are wired and passing; CI smoke build (`.github/workflows/ci.yml`) covers both platforms; doc seeds in [`docs/`](docs/) are in place. See [`MASTER_PLAN.md`](MASTER_PLAN.md) for live progress.

## Prerequisites

- **JDK 21** (Temurin recommended). Pinned via `.tool-versions` / `mise.toml`.
- **Xcode 16+** (iOS 16 deployment target, Swift 5.10+). Apple has no CLI version manager — match the version your team agrees on; CI uses the `macos-latest` image's default Xcode.
- **Ruby 3.3.x** (for Fastlane, wired up in Phase 15).
- **Android SDK** with platform 35 + build-tools 35.0.0 (installed during Phase 01 §6).
- **Gradle** is supplied by the wrapper (`./gradlew`) — do not install separately.

## Where to start

- **[`MASTER_PLAN.md`](MASTER_PLAN.md)** — the source of truth. Roadmap, milestones, progress tracker, ▶ Next Step pointer, and architecture overview. Read this first.
- **[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)** — module map, dependency graph (Mermaid), per-module responsibilities, data flow, and the Konsist-enforced architecture rules.
- **[`plan/`](plan/)** — 15 phase files, each exhaustive enough to execute against. Plus [`plan/00_DECISIONS.md`](plan/00_DECISIONS.md), the living ADR log.
- **[`docs/TEAM_GUIDELINES.md`](docs/TEAM_GUIDELINES.md)** — commit conventions, branching, PR rules, code-review SLAs.
- **[`DESIGN.md`](DESIGN.md)** — brand, color, typography, spacing, and component specs.
- **[`design/`](design/)** — reference mockups for the four core v1 screens.

## How to run — Android

Fresh-clone setup:

1. Create `local.properties` at the repo root (gitignored):

   ```properties
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```

   (Linux: typically `/home/<you>/Android/Sdk`.)

2. Optional but recommended — install the auto-format pre-commit hook:

   ```bash
   scripts/install-hooks.sh
   ```

3. Build the debug APK:

   ```bash
   ./gradlew :android-app:assembleDebug
   ```

   Output: `android-app/build/outputs/apk/debug/android-app-debug.apk`.

4. Install on a running emulator or attached device:

   ```bash
   ./gradlew :android-app:installDebug
   ```

   Or open the project in Android Studio (Hedgehog or newer) and Run.

## How to run — iOS

Fresh-clone setup (macOS only):

1. Install [`xcodegen`](https://github.com/yonaskolb/XcodeGen):

   ```bash
   brew install xcodegen
   ```

2. Run the iOS smoke build. This regenerates `ios-app/FluxIt.xcodeproj` from [`ios-app/project.yml`](ios-app/project.yml) (the project file is gitignored — `project.yml` is the source of truth) and assembles the shared XCFramework before invoking `xcodebuild`:

   ```bash
   scripts/build-ios.sh
   ```

3. To work in Xcode, run the script once to materialize the project, then:

   ```bash
   open ios-app/FluxIt.xcodeproj
   ```

   Pick the `FluxIt` scheme and Run on any iOS 16+ Simulator.

If a matching iOS Simulator runtime isn't installed, run `xcodebuild -downloadPlatform iOS` first.

## Repo layout

> The diagram below shows the v1 target shape. For the authoritative list of currently wired Gradle modules see [`settings.gradle.kts`](settings.gradle.kts); for per-module responsibilities and current vs. planned status see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

```
/android-app                  ← Compose host, Navigation Compose
/ios-app                      ← SwiftUI host, consumes shared XCFramework
/shared                       ← KMP modules
  /domain                       entities, use cases, repository contracts
  /data                         SQLDelight, repository impls
  /state                        MVI stores (exposed to iOS via SKIE)
/core
  /core-designsystem            Compose theme + SwiftUI token mirror
  /core-utils
/features
  /feature-lists                dashboard
  /feature-list-detail
  /feature-create-list
  /feature-item-detail
  /feature-account
  /feature-reminders            reminder editor
/platform
  /platform-analytics
  /platform-logging
  /platform-config
  /platform-reminders
  /platform-photo
/build-logic                  ← Gradle convention plugins
/docs                         ← architecture, decisions, scaling, team guidelines
/.github                      ← workflows, PR template, CODEOWNERS
```

## Stack (v1, locked)

- **Build**: Gradle Kotlin DSL, version catalog, `build-logic` convention plugins
- **DI**: Koin
- **Local DB**: SQLDelight 2
- **Concurrency**: Coroutines + Flow
- **Serialization**: kotlinx.serialization
- **Time**: kotlinx-datetime
- **iOS interop**: SKIE
- **State**: shared Flow-based MVI (hand-rolled `BaseStore`)
- **Navigation**: native per platform (Navigation Compose + SwiftUI `NavigationStack`)
- **Reminders**: WorkManager (Android) + UNUserNotificationCenter (iOS), behind shared port
- **Photo**: CameraX-deferred system camera + PHPicker, behind shared port
- **Logging**: Kermit + Crashlytics
- **Quality**: ktlint, detekt, Spotless, Konsist
- **CI**: GitHub Actions matrix (ubuntu + macos)

Deferred to v2: Ktor, Store5, Compose Multiplatform UI, auth, sync, Calendar/Starred tabs.

## Working in this repo

This repo uses **one feature branch per phase** (`phase/<NN>-<slug>`, e.g. `phase/02-design-system`), merged in a single PR at the phase's hand-off gate. Commits on the branch stay granular (Conventional Commits per logical change — `feat | fix | refactor | docs | test | chore | perf | build | ci`); only the merge cadence is batched. This conserves GitHub Actions minutes; rationale and exceptions (Dependabot, hotfixes, repo-level chores) live in [`docs/TEAM_GUIDELINES.md`](docs/TEAM_GUIDELINES.md). Default model until superseded by anticipated ADR-011 (Phase 15).

### Branch protection on `main`

Enforced via GitHub repo settings (Settings → Branches → Branch protection rules → `main`). The CI workflow (`.github/workflows/ci.yml`) provides the required status checks. Configure as follows:

- **Require a pull request before merging.** Direct pushes to `main` are blocked.
  - Require approvals: **1** (raise to 2 once a second reviewer joins).
  - Dismiss stale approvals when new commits are pushed.
  - Require review from **Code Owners** ([`.github/CODEOWNERS`](.github/CODEOWNERS)).
- **Require status checks to pass before merging.**
  - Required: `JVM build (Android shell + quality gates)` and `iOS build (shared XCFramework + SwiftUI shell)` from the `CI` workflow.
  - Require branches to be up to date before merging.
- **Require conversation resolution before merging.**
- **Require linear history** (no merge commits — squash or rebase only).
- **Do not allow bypassing the above settings**, including for repository administrators.
- **Restrict who can push to matching branches:** leave empty (PR-only).
- Force pushes: **blocked**. Deletions: **blocked**.

Dependabot PRs (config in [`.github/dependabot.yml`](.github/dependabot.yml)) are subject to the same gates.

## License

All rights reserved (placeholder; revisit at Phase 17 release-readiness).
