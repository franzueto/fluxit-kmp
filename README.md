# FluxIt

A high-performance list-making app for Android and iOS, built natively with **Kotlin Multiplatform**. Domain, data, and state logic shared; UI native (Jetpack Compose + SwiftUI). Dark-mode-first, offline-only in v1.

> **Status: planning complete, no code yet.** This repo currently contains the v1 architectural plan, design assets, and design tokens. Implementation begins at Phase 01.

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

This repo will follow trunk-based development with short-lived branches once Phase 01 lands the CI workflows. Until then, plan iterations are committed directly to `main`.

Conventional Commits required (`feat | fix | refactor | docs | test | chore | perf | build | ci`).

## License

All rights reserved (placeholder; revisit at Phase 17 release-readiness).
