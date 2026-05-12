# Phase 14 — Testing Strategy

> **Goal:** Define the test pyramid, coverage gates, snapshot infra, integration harness, device matrix, and manual-QA discipline that ship FluxIt safely. Each prior phase ships its own per-module tests; this phase makes the system cohesive and **enforces** quality in CI (Phase 15) so regressions can't merge.

**Owner:** Mobile platform (shared)
**Depends on:** All planning phases. Implementation can start in parallel with feature phases (test infra benefits compound).
**Blocks:** Phase 15 (CI/CD wires gates), Phase 17 (release hardening reads coverage + flake reports).
**Exit criteria (Definition of Done):**
- Coverage gates enforced per module (§3); CI fails below threshold.
- Snapshot test harness runs locally + in CI on both platforms with deterministic outputs.
- Integration test ("seed → add → toggle → schedule → reopen") runs green on JVM + iosSimulatorArm64 in < 30s.
- Flake budget: no test fails > 1× in 100 CI runs (tracked via Phase 15 retry annotations).
- Manual-QA checklist exists, is current, and is run on every release-candidate build (Phase 17).

---

## 1. Test pyramid

```
                ┌───────────────┐
                │ Manual QA     │  ~30 min / RC; release-blocker only
                │ (real device) │
                └───────────────┘
              ┌───────────────────┐
              │ UI / Instrumented │  per-screen smoke + critical paths
              │ (Compose / XCUI)  │  ~30 tests
              └───────────────────┘
           ┌─────────────────────────┐
           │ Integration             │  ~10 tests, full DB + fake ports
           │ (shared, JVM + iosSim)  │
           └─────────────────────────┘
        ┌─────────────────────────────────┐
        │ Snapshot                        │  every primitive + screen state
        │ (Paparazzi / swift-snapshot)    │  ~80 goldens
        └─────────────────────────────────┘
      ┌──────────────────────────────────────┐
      │ Unit (common + per-platform)         │  bulk of the pyramid
      │ JUnit5 / kotlin.test / Kotest / XCTest│ ~600+ tests
      └──────────────────────────────────────┘
```

## 2. Test types — what lives where

| Type | Where | What it covers | Tools |
|---|---|---|---|
| **Unit (common)** | `commonTest` per module | Domain use cases, mappers, calculators, error mapping, repository contracts via fakes | `kotlin.test`, Kotest property tests, Turbine for Flows, `runTest` + `TestScope` virtual time |
| **Unit (android)** | `androidUnitTest` | Android-only impls (Workers, FileProvider helpers, Compose ViewModel glue) | Robolectric, JUnit5, MockK |
| **Unit (ios)** | iosTest target in Xcode | iOS-only impls (notification triggers, UIImagePickerController bridges) | XCTest |
| **Snapshot** | `androidUnitTest` (Paparazzi); iOS app target | Every design-system primitive + every screen-state in every feature | Paparazzi 1.x (Compose-aware); `swift-snapshot-testing` (Point-Free) |
| **Integration (shared)** | `commonTest` JVM + iosSimulatorArm64 | Multi-component scenarios using real SQLDelight (in-memory) + fake ports | Same as unit; uses `IntegrationTestHarness` |
| **UI (Android)** | `androidInstrumentedTest` | Critical-path flows in a real emulator | Compose UI Test, Espresso (only when Compose UI Test falls short) |
| **UI (iOS)** | iOS UI Test target | Same flows, mirror coverage | XCUITest |
| **Manual QA** | Real devices | Permission flows, notifications fire, photos persist, perceived performance | Checklist (§9) |

## 3. Coverage gates

Enforced per Gradle module in CI via JaCoCo aggregated reports (Android side) and `kover` for KMP commonMain (cross-platform). Below threshold → CI fail.

| Module | Line coverage | Branch coverage | Rationale |
|---|---|---|---|
| `:shared:domain` | **95%** | **95%** | Pure code; no excuses. Use cases own validation; misses imply untested branches. |
| `:shared:data` | **90%** | **85%** | Schema + adapters + mappers; small surface for unreachable error paths. |
| `:shared:state` | **90%** | **85%** | Stores are testable end-to-end with fakes. |
| `:core:core-designsystem` | **70%** | **60%** | Snapshot tests carry most of the load; line/branch isn't the right metric for theme tokens. |
| `:core:core-utils` | **95%** | **95%** | Tiny module; everything should be covered. |
| `:platform:*` | **70%** | **60%** | OS interop is hard to unit-test; integration + manual QA fill the gap. |
| `:features:*` (Android) | **75%** | **60%** | Routes + previews drag down line numbers; behavior tests are what matter. |
| `:features:*` (iOS) | tracked, not gated | — | iOS coverage tooling on KMP frameworks is fiddly; track via Xcode reports, don't gate. |
| `:android-app` / `:ios-app` | not gated | — | Thin hosts; covered transitively. |

- [ ] Tune thresholds **after** the first month of real measurements; ratchet up only, never down (recorded as ADR amendment).
- [ ] Coverage **exclusions** (whitelist, not blanket): generated SQLDelight + token files, BuildKonfig, `Preview` / `Debug*` source sets, Konsist test sources.

## 4. `IntegrationTestHarness`

A shared fixture that gives every integration test a fully wired domain + data + state stack against an in-memory DB.

```kotlin
class IntegrationTestHarness(
    val clock: FakeClock = FakeClock(Instant.parse("2026-05-11T09:00:00Z")),
    val idGen: FakeIdGenerator = FakeIdGenerator(),
    val scheduler: FakeReminderScheduler = FakeReminderScheduler(),
    val photoCapture: FakePhotoCapture = FakePhotoCapture(),
    val photoStorage: FakePhotoStorage = FakePhotoStorage(),
) : AutoCloseable {
    val database: FluxItDatabase = FluxItDatabase(JdbcSqliteDriver(IN_MEMORY).also { Schema.create(it) }, …)
    val lists: ListsRepository = ListsRepositoryImpl(database, clock, idGen)
    val items: ItemsRepository = ItemsRepositoryImpl(database, clock, idGen)
    val reminders: RemindersRepository = RemindersRepositoryImpl(database, clock, idGen)
    val photos: PhotosRepository = PhotosRepositoryImpl(database, photoStorage, clock, idGen)
    // … use cases wired with the above …

    fun store(): ListsDashboardStore = …  // factory per store type
    override fun close() { database.close(); … }
}
```

- [ ] Lives in `:shared:state/src/commonTest/kotlin/com/fluxit/test/`.
- [ ] Used by **every** integration test; deviations require justification in PR description.
- [ ] **Exit-criteria integration test** (the headline scenario): create list → add 3 items → toggle one complete → schedule a daily reminder for 9am → close DB → reopen with same harness instance → state identical.

## 5. Snapshot testing

### Android (Paparazzi)

- [ ] Per feature module: a `*ScreenSnapshotTest.kt` enumerating every UI state from §10 of the feature plan.
- [ ] Device matrix: Pixel 5 (medium phone) + Pixel C (tablet — verifies layout doesn't break at wide widths even though tablet isn't a v1 target). Both at 1x density to keep PNG sizes manageable.
- [ ] Goldens stored in `src/androidUnitTest/snapshots/`. PR review surfaces image diffs via a GitHub Action artifact.
- [ ] Updating goldens: `./gradlew recordPaparazziDebug`. Must be done with intent — never as part of "fixing a flake."

### iOS (`swift-snapshot-testing`)

- [ ] Per feature view: a `*ViewSnapshotTests.swift` with `assertSnapshot(matching: view, as: .image(on: .iPhone13Mini))` for each state.
- [ ] Device sizes: iPhone SE (smallest supported), iPhone 13 mini, iPhone 14 Pro Max. Dark mode forced (we're dark-only per ADR-005b).
- [ ] Goldens stored in `__Snapshots__/` next to test files.

### Cross-platform parity check

- [ ] **`PrimitiveParityTest`** — for each design-system primitive, render both platforms' snapshot at the same logical size and assert pixel-similarity ≥ 95% via a small Kotlin script consuming both image outputs. Optional in v1; if cost too high, drop to manual side-by-side review during design QA. Default: drop; revisit in v2.

### Anti-flake rules for snapshot tests

- [ ] **Frozen clock**: every test that includes "Last edited on …" or "Last updated 2h ago" injects `FakeClock(fixedInstant)`.
- [ ] **Bundled fonts**: snapshots must not depend on system font fallback. Test runtime loads `Inter-Variable.ttf` from `src/test/resources/`.
- [ ] **No animations in snapshots**: disable animation specs in test theme.
- [ ] **Stable ids**: `FakeIdGenerator("test-id-")` produces `test-id-1`, `test-id-2`, … so rendered debug labels (in debug builds) are deterministic.

## 6. UI (instrumented) tests

Scope is **deliberately narrow** — exhaustive UI tests are expensive and brittle. Cover only:

- [ ] **Dashboard**: open app → see seeded lists → tap list → land on detail.
- [ ] **List Detail**: toggle item completion → assert moved between sections.
- [ ] **Composer**: type "Apples" → submit → assert in TO BUY.
- [ ] **Create List**: FAB → fill name → tap Create → land on detail of new list.
- [ ] **Edit Item**: tap row → edit name → Save → assert dashboard row reflects change.
- [ ] **Swipe-to-delete + undo**: swipe → tap Undo → assert restored.
- [ ] **Permission denial** (camera + notifications): fake denials → assert banners surface.
- [ ] **Deep link**: launch with `fluxit://list/{seed-id}` → assert detail screen.

Everything else (form validation, button enabled state, color of progress bar, etc.) is covered by snapshot + store-unit tests.

## 7. Property-based tests

Targeted, not pervasive — used where invariants are easier to state than examples.

- [ ] `SortOrderArithmetic`: 1000 random reorders never produce gap collapse without compaction.
- [ ] `RecurrenceCalculator`: `nextFireAfter` strictly advances; 100 iterations on each variant never produce non-monotonic sequences; Monthly clamping correct across `Europe/London` + `Asia/Kolkata` + `Pacific/Apia` (DST + non-DST + dateline) for full year cycles.
- [ ] `Outcome` monad laws (identity, associativity for `flatMap`) — guards against subtle map/flatMap regressions.

## 8. Performance tests (in-CI vs. on-bench)

- [ ] **In-CI** (cheap, deterministic):
  - Cold-start time of every store < 50ms with fake repos (Phase 05 exit criteria).
  - `RehydrateReminders` < 200ms on 100 active reminders with fakes.
  - `IntegrationFlowTest` end-to-end < 30s.
- [ ] **On-bench** (real device, run weekly + before release):
  - Pixel 6a + iPhone 12 mini.
  - App cold-start to dashboard with seeded 5 lists / 30 items: ≤ 800ms (Android) / ≤ 600ms (iOS).
  - Dashboard scroll with 50 lists: 60fps sustained.
  - List detail scroll with 100 items: 60fps sustained.
  - Item completion toggle animation: 60fps.
- [ ] On-bench numbers tracked in `docs/PERF_LOG.md` per release; regression = release blocker.

## 9. Manual QA checklist (release-candidate)

Lives at `docs/MANUAL_QA_CHECKLIST.md`. Executed before every TestFlight / internal-track build. ~30 minutes per platform.

- [ ] Cold install → dashboard renders empty state → tap FAB → create "Test list" → land on detail → add 3 items → tap radio on one → moves to COMPLETED → swipe one to delete → tap Undo → restored.
- [ ] Take a photo on an item (camera) → save → relaunch → photo persists.
- [ ] Pick a photo from library → save → photo persists.
- [ ] Deny camera permission → banner with Try Again works → grant via Settings → return to app → banner dismisses.
- [ ] Schedule a one-shot reminder for +1 min → lock device → wait → notification fires with correct title/body → tap → app opens to the right list.
- [ ] Schedule a Weekly Mon/Wed/Fri reminder → verify fires correctly on those days (calendar trick: set device date forward, observe).
- [ ] Schedule Monthly on the 31st → set device date to Jan 31 → fires → set forward to Feb 28 → fires.
- [ ] Kill app process mid-edit-item with unsaved description → reopen → text restored.
- [ ] Toggle theme to light mode (system) → app stays dark (per ADR-005b).
- [ ] Toggle reduce-motion in system settings → animations honor it.
- [ ] Turn on TalkBack / VoiceOver → tab through dashboard, detail, create-list → no unlabelled elements.
- [ ] Settings → "Clear all data" (debug only) → app restarts to empty state.

## 10. Test data + fakes registry

- [ ] **`SampleData`** singleton in `:shared:state/src/commonTest/` carrying the 5 mockup lists + items. Used by snapshot tests, integration tests, and the debug "Seed sample data" button (Phase 07 §7). Single source = test/QA parity.
- [ ] **Fake catalog** documented in `:shared:state/test-fixtures/README.md`: `FakeListsRepository`, `FakeItemsRepository`, `FakeRemindersRepository`, `FakePhotosRepository`, `FakeClock`, `FakeIdGenerator`, `FakeReminderScheduler`, `FakePhotoStorage`, `FakePhotoCapture`, `RecordingAppLogger`, `RecordingAnalyticsSink`. Each fake's contract is `// must match real impl semantics; deviation = bug` — verified by a small set of shared "contract tests."
- [ ] **Contract tests** for fakes: e.g. `RepositoryContractTest<T : ListsRepository>` runs the same assertions against `FakeListsRepository` and `ListsRepositoryImpl` (in-memory DB). Prevents fakes from drifting into "happy place" behavior that the real impl won't honor.

## 11. Flake management

- [ ] Every test that uses real time (`Thread.sleep`, `Espresso.idleSync`, etc.) is **banned by Konsist rule**. Use virtual time (`TestScope.advanceTimeBy`).
- [ ] CI retries failed tests **at most once**; second failure = real fail.
- [ ] Flake tracking: a failed-then-passed run is logged to `docs/FLAKE_LOG.md` (auto-appended by CI). Tests appearing > 2x in a rolling 30-day window are quarantined (`@Ignore` with a TODO link to a tracking issue).
- [ ] No `@Ignore` without an issue link. Konsist rule enforces.

## 12. Accessibility testing

- [ ] **Automated**:
  - Android: `accessibility-test-framework` integrated into Compose UI tests; runs on every instrumented test target; finding severity `Error` fails the build.
  - iOS: `XCUIApplication.performAccessibilityAudit(for:)` in XCUITest (iOS 17+).
- [ ] **Snapshot a11y**: render each primitive with `semantics`/`accessibilityElement` enabled and assert critical labels present (no missing labels on interactive elements).
- [ ] **Manual**: TalkBack + VoiceOver pass on every feature phase per its hand-off checklist.

## 13. Konsist (architecture) tests

Already enumerated across phases. Aggregated registry lives in `:build-logic/src/konsit/`:

- [ ] Domain has no Android/iOS/SQLDelight/Koin-runtime imports (Phase 04).
- [ ] `feature-*` modules don't depend on each other (Phase 01).
- [ ] No raw `Color`/`dp`/`TextStyle` outside `core-designsystem` (Phase 02).
- [ ] No `GlobalScope` / `runBlocking` outside test sources (Phase 01).
- [ ] Stores expose only `state`/`effects`/`dispatch` (Phase 05).
- [ ] No `import com.fluxit.data.*` outside `:shared:data` (Phase 03).
- [ ] No real-time waits in tests (Phase 14 §11).
- [ ] No `@Ignore` without `// TODO(FXT-XXX)` comment (Phase 14 §11).

Runs in the `:build-logic` test source set so violations break the build immediately, not at runtime.

## 14. PR-level gates

Hooked up in Phase 15; documented here so authors know the contract:

- [ ] `./gradlew check` green — runs all unit, snapshot, Konsist.
- [ ] Coverage thresholds met (per §3).
- [ ] No new `@Ignore` without an issue link.
- [ ] No new flake-log entries in the touched modules.
- [ ] If a snapshot golden changed: PR description must include a "Visual changes" section with the rationale.
- [ ] If `schema.sql` changed: PR description must include a "Migration" section with the migration plan (even if v1 has none yet — habit-forming).

## 15. ADRs to write in this phase

- [ ] **ADR-010** — Test pyramid shape + per-module coverage gates (§3). Documents the chosen thresholds and the rationale for the asymmetry between `domain` (95%) and `platform-*` (70%).
- [ ] **ADR-010a** — Snapshot tests are first-class quality gates, not just a "nice to have." Goldens diff are reviewed like code.
- [ ] **ADR-010b** — Fakes have contract tests against real impls. Prevents fake/real drift.

## 16. Resolved decisions for this phase (2026-05-11)

- ✅ **iOS coverage: tracked via Xcode reports, not gated.** Coverage numbers logged in PR comments via xccov export, but no threshold enforcement. Re-evaluate if a regression slips that coverage would have caught.
- ✅ **Cross-platform pixel parity tests: deferred to v2.** Designs validated by human design QA during snapshot review (PR-attached image diffs). The §5 "PrimitiveParityTest" idea is dropped from v1 scope; reserve the namespace for later.
- ✅ **Mutation testing: not in v1.** Property tests + Konsist + the per-store fake-vs-real contract tests (ADR-010b) cover the most common gaps. Revisit if coverage numbers feel "thin" (e.g. 90% line + property tests still missing real bugs).
- ✅ **Paparazzi: record locally, verify in CI.** Pin Temurin JDK 21 + Paparazzi's bundled native renderer in `gradle/libs.versions.toml` and verify version match in `build-logic`. Goldens checked in by developers via `./gradlew recordPaparazziDebug` on macOS; CI on `ubuntu-latest` runs `verifyPaparazziDebug` only. Document this in `docs/TEAM_GUIDELINES.md`.

## 17. Hand-off checklist (gate to Phase 15)

- [ ] All checkboxes above ✅.
- [ ] `IntegrationTestHarness` shipped; in use by ≥ 5 integration tests.
- [ ] Paparazzi + `swift-snapshot-testing` infra wired; at least one golden per feature checked in.
- [ ] Coverage report visible locally + in CI; thresholds set per §3 (not yet enforced — Phase 15 wires enforcement).
- [ ] Konsist registry complete and green.
- [ ] `docs/MANUAL_QA_CHECKLIST.md` published; first dry run completed.
- [ ] `MASTER_PLAN.md`: Phase 14 → 🟢, ▶ Next Step → Phase 15.
- [ ] `00_DECISIONS.md`: ADR-010 (a/b) accepted.
