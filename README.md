# FluxIt

A high-performance list-making app for Android and iOS, built natively with **Kotlin Multiplatform**. Domain, data, and state logic shared; UI native (Jetpack Compose + SwiftUI). Dark-mode-first, offline-only in v1.

> **Status: planning complete, no code yet.** This repo currently contains the v1 architectural plan, design assets, and design tokens. Implementation begins at Phase 01.

## Prerequisites

- **JDK 21** (Temurin recommended). Pinned via `.tool-versions` / `mise.toml`.
- **Xcode 16+** (iOS 16 deployment target, Swift 5.10+). Apple has no CLI version manager — match the version your team agrees on; CI uses the `macos-latest` image's default Xcode.
- **Ruby 3.3.x** (for Fastlane, wired up in Phase 15).
- **Android SDK** with platform 35 + build-tools 35.0.0 (installed during Phase 01 §6).
- **Gradle** is supplied by the wrapper (`./gradlew`) — do not install separately.

## Where to start

- **[`MASTER_PLAN.md`](MASTER_PLAN.md)** — the source of truth. Roadmap, milestones, progress tracker, ▶ Next Step pointer, and architecture overview. Read this first.
- **[`plan/`](plan/)** — 15 phase files, each exhaustive enough to execute against. Plus [`plan/00_DECISIONS.md`](plan/00_DECISIONS.md), the living ADR log.
- **[`DESIGN.md`](DESIGN.md)** — brand, color, typography, spacing, and component specs.
- **[`design/`](design/)** — reference mockups for the four core v1 screens.

## Repo layout (target, end of Phase 01)

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

Until Phase 01 ships these directories, only the planning files exist.

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

This repo follows **trunk-based development** with short-lived branches off `main`. Conventional Commits required (`feat | fix | refactor | docs | test | chore | perf | build | ci`); see [`docs/TEAM_GUIDELINES.md`](docs/TEAM_GUIDELINES.md) for the full convention.

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
