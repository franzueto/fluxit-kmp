# FluxIt — Master Plan

> **Source of truth.** Every other plan file is a child of this one. When a decision changes, update this file *first*.

**Last updated:** 2026-05-15
**Architect:** _you_ + Claude (Senior Mobile Architect role)
**Repo phase:** Foundation complete — Phase 01 closed (Android + iOS shells build green; all four quality gates wired; CI proven green on PR #4 plus three Dependabot PRs; doc seeds + ADR log in place; KMP test harness proven via `:core:core-utils`). Phase 02 (Design System) is up next.

---

## ▶ Next Step

**Phase 02 — Design System.** Start at [`plan/02_DESIGN_SYSTEM.md`](plan/02_DESIGN_SYSTEM.md) §1 (token source of truth) — the chosen approach is a hand-authored `core-designsystem/tokens/tokens.json` (W3C Design Tokens CG format) plus a small Gradle code-generator that emits `FluxItColors.kt` / `FluxItTypography.kt` / `FluxItShapes.kt` / `FluxItSpacing.kt` / `FluxItElevation.kt` for Compose and `FluxItTokens.swift` for SwiftUI. The first decision to surface is **ADR-005** (token pipeline) — draft it before the generator lands. Phase 02 also owes: rebranding `DESIGN.md` "Lumina Lists" → "FluxIt" (per ADR-002) and adding the missing `#2b7cee` primary color to the YAML token map.

---

## Progress at a Glance

| # | Phase | File | Status | % |
|---|---|---|---|---|
| 00 | Decisions log (ADRs) | [`00_DECISIONS.md`](plan/00_DECISIONS.md) | 🟢 Live (6 ADRs) | n/a |
| 01 | Initial Setup | [`01_INITIAL_SETUP.md`](plan/01_INITIAL_SETUP.md) | 🟢 Complete | 100% |
| 02 | Design System | [`02_DESIGN_SYSTEM.md`](plan/02_DESIGN_SYSTEM.md) | 🟠 In progress | 0% |
| 03 | Data Layer | [`03_DATA_LAYER.md`](plan/03_DATA_LAYER.md) | 🟡 Planned | 0% |
| 04 | Domain Layer | [`04_DOMAIN_LAYER.md`](plan/04_DOMAIN_LAYER.md) | 🟡 Planned | 0% |
| 05 | State Management | [`05_STATE_MANAGEMENT.md`](plan/05_STATE_MANAGEMENT.md) | 🟡 Planned | 0% |
| 06 | Platform Modules | [`06_PLATFORM_MODULES.md`](plan/06_PLATFORM_MODULES.md) | 🟡 Planned | 0% |
| 07 | Feature: Lists Dashboard | [`07_FEATURE_LISTS_DASHBOARD.md`](plan/07_FEATURE_LISTS_DASHBOARD.md) | 🟡 Planned | 0% |
| 08 | Feature: List Detail | [`08_FEATURE_LIST_DETAIL.md`](plan/08_FEATURE_LIST_DETAIL.md) | 🟡 Planned | 0% |
| 09 | Feature: Create List | [`09_FEATURE_CREATE_LIST.md`](plan/09_FEATURE_CREATE_LIST.md) | 🟡 Planned | 0% |
| 10 | Feature: Item Detail (Photo) | [`10_FEATURE_ITEM_DETAIL.md`](plan/10_FEATURE_ITEM_DETAIL.md) | 🟡 Planned | 0% |
| 11 | Feature: Calendar / Starred (deferred) | _to be created_ | ⚪ Deferred to v2 | 0% |
| 12 | Offline Sync (deferred) | _to be created_ | ⚪ Deferred to v2 | 0% |
| 13 | Notifications & Reminders | [`13_NOTIFICATIONS_REMINDERS.md`](plan/13_NOTIFICATIONS_REMINDERS.md) | 🟡 Planned | 0% |
| 14 | Testing Strategy | [`14_TESTING_STRATEGY.md`](plan/14_TESTING_STRATEGY.md) | 🟡 Planned | 0% |
| 15 | CI/CD | [`15_CI_CD.md`](plan/15_CI_CD.md) | 🟡 Planned | 0% |
| 16 | Observability | [`16_OBSERVABILITY.md`](plan/16_OBSERVABILITY.md) | 🟡 Planned | 0% |
| 17 | Release Hardening | [`17_RELEASE_HARDENING.md`](plan/17_RELEASE_HARDENING.md) | 🟡 Planned | 0% |

**Overall v1 progress: 7% (1 / 14 active phases complete)**
_Phases 11 & 12 are explicitly out of v1 scope (see ADR-003, ADR-004)._

---

## Roadmap (Milestones)

### M1 — Foundations *(Phases 01–02)*
Repo scaffold, Gradle KMP, version catalog, `build-logic` convention plugins, lint/format, CI smoke build, design tokens encoded in both Compose theme and SwiftUI mirror.
**Exit criteria:** `./gradlew build` green; iOS framework builds via Xcode; `core-designsystem` exposes color/type/spacing tokens; Konsist architecture tests pass on an empty graph.

### M2 — Data + Domain *(Phases 03–05)*
SQLDelight schema (`List`, `Item`, `Reminder`, `Photo`), migrations, repositories, use cases, shared MVI stores, SKIE-exposed flows.
**Exit criteria:** Headless integration test creates a list, adds items, toggles completion, schedules a reminder, persists across process restarts.

### M3 — Platform Plumbing *(Phases 06, 13, 16)*
Expect/actual for reminders, photo capture, analytics, logging, config; permission UX; deep links from notifications.
**Exit criteria:** Real local notification fires on Android *and* iOS; Crashlytics receives a synthetic crash from both targets.

### M4 — Core User Surfaces *(Phases 07–10)*
Lists Dashboard → List Detail → Create List → Edit Item with Photo. Native Compose + SwiftUI screens against shared stores.
**Exit criteria:** All four screens from `/design` reachable end-to-end on both platforms with offline persistence.

### M5 — Quality & Release *(Phases 14, 15, 17)*
Test pyramid filled out, CI matrix green, signing, store metadata, accessibility audit, perf budgets, R8.
**Exit criteria:** Internal track build distributed via Firebase App Distribution + TestFlight; PR template + CODEOWNERS in place.

### v2 Backlog *(Phases 11, 12, plus reactivated networking stack)*
- Calendar tab (reminder agenda)
- Starred tab (filtered cross-list view)
- Backend + multi-device sync (Ktor + Store5 + auth + conflict resolution)
- Compose Multiplatform pilot for one screen if `kotlin-stdlib` reduction & native feel can be proven

---

## Architecture Overview

### Layering (Clean Architecture, dependencies point inward)

```
┌─────────────────────────────────────────────────────────────┐
│  android-app (Compose)              ios-app (SwiftUI)        │  ← Platform UI
├─────────────────────────────────────────────────────────────┤
│  feature-* (shared state stores, presentation models)        │  ← Shared MVI
├─────────────────────────────────────────────────────────────┤
│  domain  (entities, use cases, repository contracts)         │  ← Pure Kotlin
├─────────────────────────────────────────────────────────────┤
│  data    (SQLDelight, repositories, mappers)                 │  ← Shared
├─────────────────────────────────────────────────────────────┤
│  core-* (designsystem, utils)   platform-* (analytics,       │  ← Capability
│                                  logging, config, reminders, │
│                                  photo, notifications)       │
└─────────────────────────────────────────────────────────────┘
```

### Module Topology (target end-of-M2)

```
/android-app                     ← Compose host, Navigation Compose, DI bootstrap
/ios-app                         ← SwiftUI host, SKIE-consumed shared framework
/shared
  /domain                        ← entities, use cases, repository contracts
  /data                          ← SQLDelight, repository impls, mappers
  /state                         ← MVI stores per feature, exposed via SKIE
/core
  /core-designsystem             ← tokens (Compose theme + SwiftUI mirror generator)
  /core-utils                    ← Result, dispatchers, time, id gen
/features
  /feature-lists                 ← dashboard + create-list state
  /feature-list-detail           ← items, sections, composer state
  /feature-item-detail           ← edit item + photo state
  /feature-account               ← v1 stub
/platform
  /platform-analytics            ← expect/actual sink
  /platform-logging              ← Kermit + Crashlytics bridge
  /platform-config               ← BuildKonfig, feature flags
  /platform-reminders            ← WorkManager / UNUserNotificationCenter
  /platform-photo                ← CameraX / PHPicker
/build-logic                     ← convention plugins (kmp.library, kmp.feature, android.app)
/docs                            ← ARCHITECTURE.md, DECISIONS.md, SCALING.md, TEAM_GUIDELINES.md
/.github                         ← workflows, PR template, CODEOWNERS
```

### Key Architectural Rules (enforced via Konsist)

1. `domain` has zero dependencies on `data`, `platform`, or any UI module.
2. `feature-*` modules cannot depend on each other (cross-feature flows go through `domain` use cases).
3. `platform-*` modules expose interfaces only; implementations live in `androidMain` / `iosMain`.
4. `core-designsystem` is the only module allowed to define color/type/spacing literals.
5. No module may import `kotlinx.coroutines.GlobalScope` or `runBlocking` outside test source sets.

### Data Flow (single source of truth)

```
SQLDelight  ──Flow──▶  Repository  ──Flow──▶  UseCase  ──▶  Store (MVI)
                                                              │
                                                              ▼
                                          State (StateFlow) ──▶ UI (Compose / SwiftUI via SKIE)
                                                              ▲
                                                              │
                                          Intent ◀── User interaction
```

Optimistic updates: stores apply intent → emit optimistic state → call use case → reconcile on emission. Errors revert via the same Flow.

---

## Locked Stack (v1)

See [`plan/00_DECISIONS.md`](plan/00_DECISIONS.md) for rationale.

Build: Gradle KDSL + version catalog + `build-logic` · DI: **Koin** · DB: **SQLDelight 2** · Serialization: **kotlinx.serialization** · Time: **kotlinx-datetime** · Concurrency: Coroutines/Flow · Logging: **Kermit** · iOS interop: **SKIE** · State: shared Flow-based **MVI** · Nav: native (Navigation Compose / SwiftUI `NavigationStack`) · Reminders: WorkManager + UNUserNotificationCenter behind shared port · Photo: CameraX + PHPicker behind shared port · Quality: ktlint + detekt + Spotless + Konsist · CI: GitHub Actions matrix.

**Deferred to v2:** Ktor, Store5, Decompose/Voyager/Circuit, Compose Multiplatform UI, auth.

---

## How We Update This File

1. When a phase file's checkboxes change, update its row's % in the table above.
2. When a phase completes, flip its status emoji to 🟢 and advance **▶ Next Step**.
3. When a decision changes, add a new ADR in `00_DECISIONS.md` and link it from the affected phase.
4. Never silently retire scope — move it to the **v2 Backlog** section with a one-line reason.
