# FluxIt — Master Plan

> **Source of truth.** Every other plan file is a child of this one. When a decision changes, update this file *first*.

**Last updated:** 2026-06-02 (**Phase 07 Feature: Lists Dashboard → 🟢 Complete** on `phase/07-feature-lists-dashboard`. Eight slices shipped the dashboard end-to-end on both platforms: `:features:feature-lists` module + `FluxItSwipeRow`/list-identity DS mappers + `RootStore` `fluxit://` deep-link parsing; the Android (Compose `NavHost` + tab host + center FAB) and iOS (`NavigationStack`-per-tab) app shells; the DS-composed dashboard screen with search, swipe-to-delete + 5s undo, empty/empty-search/error/loading states, and exhaustive effect→nav mapping; Account + Settings stub + `commonMain` `SeedSampleData` debug action; and the Slice-8 close-out — `subtitleFor`/`relativeTime` unit tests, with **snapshot infra (Paparazzi + swift-snapshot-testing) deliberately deferred to v2** per the standing scope decision (effect/tab coverage lives in the `:shared:state` store suite + exhaustive `when`/`switch`). Calendar/Starred render inline "Coming soon" (ADR-004). On-device/sim demo + a11y/perf passes are the user's manual step. **Next: Phase 08 — Feature: List Detail.** _Prior:_ **Phase 06 Platform Modules → 🟢 Complete** on `phase/06-platform-modules`. Slices 1–5 shipped the five `:platform:*` actuals — Kermit `AppLogger`; `ConfigProvider` + real `Clock`/`IdGenerator`; `LoggingAnalyticsSink`; `ReminderScheduler` (WorkManager / `UNUserNotificationCenter`); `PhotoStorage`/`PhotoCapture`/`PhotoEncoder` + the §7 host-holder — each a Koin module, guarded by a Konsist `PlatformLayerArchTest`. Slice 6 swapped interim → real ports in `initKoin` (deleted `InterimPlatformModule.kt`). Slice 7 stood up the composition-root UI: `:android-app` `initKoinAndroid` (Koin + `androidContext` + `SqlDriver`) → `MainActivity` host-holder wiring + Compose `NavHost` off `RootStore`; iOS `@main App` starts Koin + `NavigationStack` → Lists Dashboard via SKIE resolvers; both reach `RootStore` Ready. Slice 8 closed out: Android backup excludes for `fluxit.db` + photos (ADR-017), capability ADRs Accepted (**ADR-008** Koin-injected ports, **ADR-016** WorkManager, **ADR-017** backups), hand-off checklist green. On-device/sim capture+reminder QA is the user's pass (per the Phase 06 scope decision). **Next: Phase 07 — Feature: Lists Dashboard**, after the Phase 06 PR merges.)
**Architect:** _you_ + Claude (Senior Mobile Architect role)
**Repo phase:** Phase 07 (Feature: Lists Dashboard) **🟢 complete** on branch `phase/07-feature-lists-dashboard` (ready to PR; off the merged Phase 06 `main`). The polished dashboard ships end-to-end on both platforms — DS-composed rows, search, optimistic delete/undo, navigation, app shell + tab host, deep links, debug seed. Snapshot infra is deferred to v2 by standing scope decision. **Next up Phase 08 (Feature: List Detail).**

---

## ▶ Next Step

**Phase 08 — Feature: List Detail.** **Phase 07 (Feature: Lists Dashboard) is 🟢 complete** as of 2026-06-02: eight slices shipped the dashboard end-to-end on Android (Compose) + iOS (SwiftUI) against `ListsDashboardStore`/`RootStore`, built from `core-designsystem` primitives only (Konsist literal-ban green). Highlights: the `:features:feature-lists` module + `FluxItSwipeRow` DS primitive; `fluxit://` deep-link parsing; per-platform app shells (tab host + center FAB + nav graph); the dashboard screen with search, swipe-to-delete + 5s undo, and exhaustive effect→nav mapping; Account + Settings stub + `commonMain` `SeedSampleData` debug action; and the Slice-8 close-out (pure-logic `subtitleFor`/`relativeTime` unit tests). **Snapshot infra (Paparazzi + swift-snapshot-testing) is deliberately deferred to v2** by standing scope decision — v1 leans on the `:shared:state` store suite (covers every `ListsEffect`/`RootEffect` variant + tab routing), the exhaustive compile-time `when`/`switch`, the new unit tests, and Konsist; UI-instrumented + a11y/perf passes are the user's on-device manual step. Gates green throughout: touched-module `:check` + `:build-logic:test --rerun-tasks` + `scripts/test-ios.sh` (iOS-facing slices). **First: open the Phase 07 PR and merge it** (the branch is local-only). **Then** start Phase 08 — see [`plan/08_FEATURE_LIST_DETAIL.md`](plan/08_FEATURE_LIST_DETAIL.md).

**Carried forward into Phase 05+ (documented, non-blocking — none gated the Phase 04 flip):**
- **Data-layer-blocked §7 use cases:** `UndoDeleteList`/`UndoDeleteItem`/`RestoreItems` + the `ClearCompletedItems → List<ItemId>` variant — need a data-layer **restore** (`deleted_at = NULL`) / `RETURNING id` method. `DeleteList` already returns `DeletedListSummary` (incl. `cancelledReminderIds`) so `UndoDeleteList` lands as a pure delegate once that primitive exists. **Now consumed (Phase 05 Slice 4/6):** `ListsDashboardStore.undoDelete` + `ListDetailStore.undoDelete` ship the full timer/snackbar undo mechanics with the restore left as a documented `TODO(data-layer)` — they only dismiss the snackbar, the soft-delete stands; `ClearCompletedClicked` likewise has no bulk-undo.
- **`CreateList` analytics** — `AnalyticsEvent.ListCreated` emission needs the §5 `AnalyticsSink` port.
- **Batch `PhotoJanitor selectOrphaned(olderThan = 24h)`** startup sweep + the janitor step inside `InitializeApp` — need a `PhotosRepository` enumeration primitive. Only the per-photo `PhotoJanitor` ships.
- **§5 ports:** `AppLogger` **built** (Phase 05 Slice 2, `:shared:domain/port` + `NoOp`; Kermit-backed actual still deferred to Phase 06). `AnalyticsSink` / `ConfigProvider` unbuilt — land with their first consumer (likely Phase 06).
- **`AttachPhotoToItem` capture/ingest split** (Phase 05 Slice 6) — `ItemDetailStore`'s `PhotoStatus.Uploading` is in the §4 contract but **unreachable** because `AttachPhotoToItem` runs capture+ingest+attach as one atomic suspend call. Surfacing the distinct `Uploading` state needs that use case split into discrete capture + ingest steps; until then the whole acquire span shows `Capturing`.
- **§11 property tests** (`SortOrderArithmetic`/`RecurrenceCalculator`, Kotest property runtime) + the §11 Konsist KDoc / `DomainError`-is-`data` enforcing rules — deferred to Phase 14; conditions already hold, only the enforcement is deferred.
- **§12 product caps** — list-name + item-description length caps deferred to the feature phase that owns the editor (Phase 09/10), landing as `ValidationError.TooLong` branches. _(Interim: Phase 05 `CreateListStore` carries a presentation-only `NAME_MAX_LEN = 100` for live field feedback; the authoritative domain cap still lands here.)_
- **Phase 05 → 06 hand-off (state-layer wiring/gates, §8/§12/§15):** **CLOSED in the Phase 05 close-out (Slices A–C + follow-up, ADR-015).** Koin `stateModule` + the full DI graph assembled in `:shared:state` (`factory` per-screen stores + `single RootStore`); the iOS smoke upgraded to a live runtime dispatch→state round-trip; the `:shared:state` Kover gate now actually runs (Slice A had left the plugin unapplied) and the close-out follow-up added the feature-store error/edge-branch tests that bring store branch coverage to ≈92%, restoring the §12 ≥90% `minValue`. No outstanding Phase 05 items.
- **Phase 02 carry-forward:** wire `verifyTokensInSync` + `verifyIconsInSync` into `.github/workflows/ci.yml` (ADR-007a's parity check rides on this).
- **Phase 07 backfill:** `FluxItSwipeRow` + long-press wiring to ThemeGallery + optional `Font.fluxIt.*` SwiftUI accessor.

---

## Progress at a Glance

| # | Phase | File | Status | % |
|---|---|---|---|---|
| 00 | Decisions log (ADRs) | [`00_DECISIONS.md`](plan/00_DECISIONS.md) | 🟢 Live (19 Accepted + 1 Superseded — Phase 06 close-out added ADR-008 Koin-injected capability ports, ADR-016 WorkManager over AlarmManager, ADR-017 Android backups disabled) | n/a |
| 01 | Initial Setup | [`01_INITIAL_SETUP.md`](plan/01_INITIAL_SETUP.md) | 🟢 Complete | 100% |
| 02 | Design System | [`02_DESIGN_SYSTEM.md`](plan/02_DESIGN_SYSTEM.md) | 🟢 Complete | 100% |
| 03 | Data Layer | [`03_DATA_LAYER.md`](plan/03_DATA_LAYER.md) | 🟢 Complete | 100% |
| 04 | Domain Layer | [`04_DOMAIN_LAYER.md`](plan/04_DOMAIN_LAYER.md) | 🟢 Complete | 100% |
| 05 | State Management | [`05_STATE_MANAGEMENT.md`](plan/05_STATE_MANAGEMENT.md) | 🟢 Complete | 100% |
| 06 | Platform Modules | [`06_PLATFORM_MODULES.md`](plan/06_PLATFORM_MODULES.md) | 🟢 Complete | 100% |
| 07 | Feature: Lists Dashboard | [`07_FEATURE_LISTS_DASHBOARD.md`](plan/07_FEATURE_LISTS_DASHBOARD.md) | 🟢 Complete | 100% |
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

**Overall v1 progress: 50% (7 of 14 active phases complete)**
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
