# FluxIt — Master Plan

> **Source of truth.** Every other plan file is a child of this one. When a decision changes, update this file *first*.

**Last updated:** 2026-05-29 (**Phase 04 Domain Layer → 🟢 Complete** on `phase/04-domain-layer`. Close-out Slices 14A→14C: §9 concurrency contract audited + KDoc'd across all 25 use cases (dispatcher-agnostic, cold Flows, `GlobalScope`/`runBlocking` banned by Konsist); §13 coverage gate wired via **Kover** (`koverVerify` ≥95% branch on `usecase/`, `dependsOn` `check`) — **measured 95.12%**, the 4 residual misses are trivial guards; §13 hand-off checklist closed. ADR-007/007a/007b Accepted, 006c Superseded (audited, unchanged). Deferrals carried forward (non-blocking): data-layer-blocked undo/restore + analytics + batch janitor, §5 `AppLogger`/`AnalyticsSink`/`ConfigProvider`, §11 property tests + Konsist KDoc rules, §12 length caps. **Next: Phase 05 — State Management (MVI stores), ADR-014 first.**)
**Architect:** _you_ + Claude (Senior Mobile Architect role)
**Repo phase:** Phase 04 (Domain Layer) **🟢 complete** on branch `phase/04-domain-layer` (ready to merge to `main`); **next up Phase 05 (State Management)**. Slices 1–9 closed §1–§3 + §5 first wave + §6 + §8 + §11 wave one. Slice 10: §11 repository fakes wave two — `FakeRemindersRepository` (MutableStateFlow-backed; `cancel` tombstones + flips `isActive`; `observeUpcoming(limit)` snapshots `clock.now()` once at subscription via `flow { … emitAll(state.map {…}) }` matching the §5 spec) + `FakePhotosRepository` (file-first-then-row contract via injected `FakePhotoStorage`; `deleteIfOrphaned` honors an injected `isReferenced: (PhotoId) -> Boolean` callback — defaults to `{ false }`, use-case wiring passes a real check against `FakeItemsRepository.state` when `PhotoJanitor` lands). `FakePhotoStorage` test helper under `port/`. 15 new tests green on JVM + iOS Sim. All four §11 repository fakes now in place. Pending list renumbered: the Phase 05 MVI ADR moved from collision-spot "ADR-007" to ADR-014.

---

## ▶ Next Step

**Phase 05 — State Management (MVI stores).** **Phase 04 (Domain Layer) is 🟢 complete** as of 2026-05-29: the full §7 use-case layer (Lists/Items/Reminders/Photos + `InitializeApp`), the four ports (`Clock`/`IdGenerator`/`ReminderScheduler`/`PhotoCapture`), the six-variant `DomainError` + `Outcome` + `Optional<T>`, and the §8 pure rules all ship with per-use-case tests — green on JVM + iOS Sim, **use-case branch coverage 95.12%** enforced by a Kover `koverVerify` rule wired into `:shared:domain:check`. §9 concurrency contract audited + KDoc'd; §13 hand-off checklist closed. **Next** starts Phase 05: shared Flow-based MVI stores per feature, exposed to iOS via SKIE — see [`plan/05_STATE_MANAGEMENT.md`](plan/05_STATE_MANAGEMENT.md). The Phase 05 MVI store contract is **ADR-014** (intents/state/effects, error model, optimistic-update pattern) — write it as the first Phase 05 slice.

**Carried forward into Phase 05+ (documented, non-blocking — none gated the Phase 04 flip):**
- **Data-layer-blocked §7 use cases:** `UndoDeleteList`/`UndoDeleteItem`/`RestoreItems` + the `ClearCompletedItems → List<ItemId>` variant — need a data-layer **restore** (`deleted_at = NULL`) / `RETURNING id` method. `DeleteList` already returns `DeletedListSummary` (incl. `cancelledReminderIds`) so `UndoDeleteList` lands as a pure delegate once that primitive exists. **Now consumed (Phase 05 Slice 4/6):** `ListsDashboardStore.undoDelete` + `ListDetailStore.undoDelete` ship the full timer/snackbar undo mechanics with the restore left as a documented `TODO(data-layer)` — they only dismiss the snackbar, the soft-delete stands; `ClearCompletedClicked` likewise has no bulk-undo.
- **`CreateList` analytics** — `AnalyticsEvent.ListCreated` emission needs the §5 `AnalyticsSink` port.
- **Batch `PhotoJanitor selectOrphaned(olderThan = 24h)`** startup sweep + the janitor step inside `InitializeApp` — need a `PhotosRepository` enumeration primitive. Only the per-photo `PhotoJanitor` ships.
- **§5 ports:** `AppLogger` **built** (Phase 05 Slice 2, `:shared:domain/port` + `NoOp`; Kermit-backed actual still deferred to Phase 06). `AnalyticsSink` / `ConfigProvider` unbuilt — land with their first consumer (likely Phase 06).
- **`AttachPhotoToItem` capture/ingest split** (Phase 05 Slice 6) — `ItemDetailStore`'s `PhotoStatus.Uploading` is in the §4 contract but **unreachable** because `AttachPhotoToItem` runs capture+ingest+attach as one atomic suspend call. Surfacing the distinct `Uploading` state needs that use case split into discrete capture + ingest steps; until then the whole acquire span shows `Capturing`.
- **§11 property tests** (`SortOrderArithmetic`/`RecurrenceCalculator`, Kotest property runtime) + the §11 Konsist KDoc / `DomainError`-is-`data` enforcing rules — deferred to Phase 14; conditions already hold, only the enforcement is deferred.
- **§12 product caps** — list-name + item-description length caps deferred to the feature phase that owns the editor (Phase 09/10), landing as `ValidationError.TooLong` branches. _(Interim: Phase 05 `CreateListStore` carries a presentation-only `NAME_MAX_LEN = 100` for live field feedback; the authoritative domain cap still lands here.)_
- **Phase 05 → 06 hand-off (state-layer wiring/gates, §8/§12/§15):** Koin `stateModule` (`@Factory` per-screen stores + `@Single` `RootStore`; no DI graph assembled yet — stores use constructor injection for now), the **live runtime iOS smoke** (dispatch→effect round-trip from Swift, once a DI-wired Swift-constructible store exists; Slice 5 shipped the compile-level smoke), and the `:shared:state` ≥90% **Kover** rule (not yet wired). All five §4 feature stores themselves are **done** (Phase 05 Slice 6).
- **Phase 02 carry-forward:** wire `verifyTokensInSync` + `verifyIconsInSync` into `.github/workflows/ci.yml` (ADR-007a's parity check rides on this).
- **Phase 07 backfill:** `FluxItSwipeRow` + long-press wiring to ThemeGallery + optional `Font.fluxIt.*` SwiftUI accessor.

---

## Progress at a Glance

| # | Phase | File | Status | % |
|---|---|---|---|---|
| 00 | Decisions log (ADRs) | [`00_DECISIONS.md`](plan/00_DECISIONS.md) | 🟢 Live (15 Accepted + 1 Superseded after Phase 04 Slice 11A flipped ADR-007 + ADR-007b) | n/a |
| 01 | Initial Setup | [`01_INITIAL_SETUP.md`](plan/01_INITIAL_SETUP.md) | 🟢 Complete | 100% |
| 02 | Design System | [`02_DESIGN_SYSTEM.md`](plan/02_DESIGN_SYSTEM.md) | 🟢 Complete | 100% |
| 03 | Data Layer | [`03_DATA_LAYER.md`](plan/03_DATA_LAYER.md) | 🟢 Complete | 100% |
| 04 | Domain Layer | [`04_DOMAIN_LAYER.md`](plan/04_DOMAIN_LAYER.md) | 🟢 Complete | 100% |
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

**Overall v1 progress: 29% (4 of 14 active phases complete)**
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
