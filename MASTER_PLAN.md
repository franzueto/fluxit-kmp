# FluxIt — Master Plan

> **Source of truth.** Every other plan file is a child of this one. When a decision changes, update this file *first*.

**Last updated:** 2026-05-28 (Phase 04 §2/§3 shape reconciliation landed on Slice 4 of `phase/04-domain-layer`)
**Architect:** _you_ + Claude (Senior Mobile Architect role)
**Repo phase:** Phase 04 (Domain Layer) **in progress** on branch `phase/04-domain-layer`. Slice 1: ADRs 007/007a/007b. Slice 2: §1 Konsist forbidden-imports test. Slice 3: §2 value-object gap fill (`RelativePath`, `TrimmedNonBlank`, `ValidationError` seed). Slice 4: §2/§3 shape reconciliation — `ReminderOwner` was already shipped in spec form (sealed interface with `OfList`/`OfItem` variants; the enum `ReminderOwnerType` is storage-side only), no churn needed; `ItemPatch` stays as a full-replacement payload at the data edge (**Option A**, user-confirmed) with the `Optional<T>` "don't touch vs. set to null" concept pushed to the use-case API in §7 (read-current → apply partial intent → emit complete `ItemPatch`). All §3 entity rows ticked with carry-forward / deferral annotations; `TrimmedNonBlank` re-typing of `ListDraft.name` / `ItemDraft.title` deferred to the §7 use-case slices that own the validation. Pending list renumbered: the Phase 05 MVI ADR moved from collision-spot "ADR-007" to ADR-014.

---

## ▶ Next Step

**Phase 04 Slice 5 — §5 platform ports first wave (`Clock` + `IdGenerator`).** With shapes settled, the next slice adds the two seed ports the rest of §7's use cases will need from the moment they start orchestrating: `Clock` (`fun now(): Instant`, production binds to `kotlinx.datetime.Clock.System`, tests inject `FakeClock(initial, advanceBy(Duration))`) and `IdGenerator` (`fun newId(): String`, production binds to `:core:core-utils`'s `expect fun newId()` per ADR-006a, tests inject `FakeIdGenerator(prefix = "id")`). Drops the `port/` directory into `:shared:domain` and unblocks the `ListId.Companion.new(idGen)` factory (§2 row) + all §7 use cases that need wall-clock or id minting. Heavier ports (`ReminderScheduler`, `PhotoCapture`, `AppLogger`, `AnalyticsSink`, `ConfigProvider`) wait for the slices that consume them. Phase 02 carry-forward still pending for a future cycle: wire `verifyTokensInSync` + `verifyIconsInSync` into `.github/workflows/ci.yml` (ADR-007a's parity check rides on this); Phase 07 backfills `FluxItSwipeRow` + long-press wiring to ThemeGallery + optional `Font.fluxIt.*` SwiftUI accessor.

---

## Progress at a Glance

| # | Phase | File | Status | % |
|---|---|---|---|---|
| 00 | Decisions log (ADRs) | [`00_DECISIONS.md`](plan/00_DECISIONS.md) | 🟢 Live (13 Accepted + 1 Superseded + 2 Proposed after Phase 04 Slice 1) | n/a |
| 01 | Initial Setup | [`01_INITIAL_SETUP.md`](plan/01_INITIAL_SETUP.md) | 🟢 Complete | 100% |
| 02 | Design System | [`02_DESIGN_SYSTEM.md`](plan/02_DESIGN_SYSTEM.md) | 🟢 Complete | 100% |
| 03 | Data Layer | [`03_DATA_LAYER.md`](plan/03_DATA_LAYER.md) | 🟢 Complete | 100% |
| 04 | Domain Layer | [`04_DOMAIN_LAYER.md`](plan/04_DOMAIN_LAYER.md) | 🔵 In progress | 22% |
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

**Overall v1 progress: 21% (3 of 14 active phases complete)**
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
