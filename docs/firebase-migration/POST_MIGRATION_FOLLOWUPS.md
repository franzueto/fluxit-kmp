# Post-migration follow-ups

The Firebase migration closed on 2026-10-06 for the development-only scope (DEC-012). This
page tracks the work worth doing after `epic/firebase` is merged. Each item started as one
or more non-blocking review findings in `FIREBASE_MIGRATION_STATUS.md` → *Non-blocking
follow-ups*; the source IDs are listed so the history can still be found. None of them
blocks the merge.

This page replaces the four-agent workflow for this work. Update the **Status** column
when you start or finish an item, and add a line to its *Log*.

**Status values:** `TODO` · `IN_PROGRESS` · `DONE` · `WONTFIX` (record why in the log).

## Summary

| ID | Priority | Item | Status | Source findings |
|---|---|---|---|---|
| [PM-01](#pm-01--shared-repository-error-classification) | High | Shared repository error classification | DONE | FB-402-NB1, FB-403-NB2, FB-406-NB2, FB-204-NB3 |
| [PM-02](#pm-02--swipe-to-delete-row-stuck-after-a-failed-or-skipped-delete) | Medium | Swipe-to-delete row stuck after a failed or skipped delete | DONE | FB-710-NB1, FB-710-NB2, FB-710-NB3 |
| [PM-03](#pm-03--move-ios-test-harnesses-out-of-the-shipping-app) | Medium | Move iOS test harnesses out of the shipping app | TODO | FB-103-NB3, FB-307-NB1 |
| [PM-04](#pm-04--photo-upload-error-contract-for-direct-callers) | Low | Photo upload error contract for direct callers | TODO | FB-602-NB1 |
| [PM-05](#pm-05--test-coverage-gaps) | Low | Test coverage gaps | TODO | FB-007-NB1, FB-304-NB2, FB-105-NB2 |
| [PM-06](#pm-06--session-restore-outcome-contract) | Low | Session-restore outcome contract | TODO | FB-104-NB1 |
| [PM-07](#pm-07--continuous-integration-optional) | Optional | Continuous integration | TODO | FB-206-NB2 |
| [PM-08](#pm-08--remove-migration-tracking-references-from-code-and-docs) | Last | Remove migration-tracking references from code and docs | TODO (after PM-01…PM-07) | — |

Suggested order: PM-01 → PM-02 → PM-03, then the low-priority items as time allows.
PM-08 goes last, once everything above is `DONE` or `WONTFIX`.

---

## PM-01 — Shared repository error classification

**Priority:** High · **Status:** DONE · **Source:** FB-402-NB1, FB-403-NB2, FB-406-NB2, FB-204-NB3

**Problem (checked in code 2026-10-06).** `ListRepositoryException` is `internal` to each
platform source set (`androidMain/.../firebase/list/FirestoreErrorMapping.kt:16`,
`iosMain/.../firebase/list/IosFirestoreErrorMapping.kt:16`), so shared code cannot read the
mapped error. `DashboardViewModel`, `CreateListViewModel`, `ListDetailViewModel` and
`ItemDetailViewModel` therefore report every list/item repository failure as
`RepositoryErrorCode.UNKNOWN` with `canRetry = true`. A permission-denied write (for example
after a Rules denial or a revoked session) shows as a retryable "unknown error" and offers a
retry that can never succeed. The item repository also reuses the list exception type
(FB-204-NB3).

**Approach.** Follow the shape already used for photos: `PhotoStorageException` is a
`commonMain` class carrying an `ApplicationError` (`commonMain/.../data/PhotoBridges.kt:110`).
Add a `commonMain`-visible repository exception, used by both the list and item adapters,
and have the ViewModels' error helpers read its `ApplicationError` instead of falling back
to `UNKNOWN`. Keep `UNKNOWN` only for truly unrecognised throwables.

**Done when:**
- [x] List and item adapters on both platforms throw a `commonMain`-visible exception carrying the mapped `ApplicationError`.
- [x] The four ViewModels surface the mapped code; permission-denied is shown as not retryable.
- [x] Shared tests cover at least permission-denied, offline/timeout and unknown for list and item operations.
- [x] Android unit, iOS simulator and the affected instrumented tests pass.

**Log:**
- 2026-10-06: Added `RepositoryException(error: ApplicationError)` to `commonMain` (`data/remote/FirebaseContracts.kt`) and removed both platform-`internal` `ListRepositoryException` classes; list and item adapters (and `toListRepositoryException()`, now `toRepositoryException()`) use it, which also settles FB-204-NB3. Added shared `Throwable.toRepositoryApplicationError()`; the four ViewModels use it (`ItemDetailViewModel` keeps its extra `PhotoStorageException` branch) and their per-ViewModel `UNKNOWN` helpers are gone. New tests: forbidden/offline/timeout for Dashboard, CreateList, ListDetail and ItemDetail, plus a mapper unit test. Two instrumented tests that asserted the old retryable-`UNKNOWN` behaviour now assert `FORBIDDEN`, not retryable (`DashboardViewModelEmulatorIntegrationTest`, `ListDetailViewModelDeleteListCrashInstrumentedTest`). Verified: Android Debug and Release unit tests, iOS simulator tests, and the instrumented suite against the local emulators (89 tests; the one failure was the stale assertion above, fixed and re-run green).

---

## PM-02 — Swipe-to-delete row stuck after a failed or skipped delete

**Priority:** Medium · **Status:** DONE · **Source:** FB-710-NB1, FB-710-NB2, FB-710-NB3

**Problem.** In `SwipeToDeleteContainer` (`commonMain/.../ui/components/SwipeToDelete.kt`):
- When a swipe-delete fails (ViewModel reports a retryable error and the item stays), the
  row stays in the swiped-away position until the delete succeeds or the row leaves and
  re-enters the list. This behaviour predates FB-710.
- `enabled` is read when the row finishes settling, not when the swipe starts. If it turns
  false during the settle animation (a pending id appears or a list delete starts),
  `onDelete` is skipped and the row stays swiped away.

**Approach.** Reset the row to its resting position when the delete is not carried out:
either the container resets itself when `onDelete` is skipped, or the ViewModel exposes a
failed-delete signal the row reacts to. Prefer the smallest change that covers both cases.

**Done when:**
- [x] A failed delete returns the row to its resting position, and the error is still shown.
- [x] A row disabled during the settle animation returns to rest instead of staying swiped.
- [x] Tests cover both cases (Android instrumented and/or iOS `compose.uiTest`), including enabled toggling mid-swipe (FB-710-NB3).
- [x] Existing swipe/Undo tests (`SwipeToDeleteContainerInstrumentedTest`, `SwipeUndoScreensInstrumentedTest`, `SwipeToDeleteContainerIosTest`) still pass.
- [x] Manual check on Android emulator and iOS simulator: swipe-delete, Undo, and a forced failure (waived, see log).

**Log:**
- 2026-10-06: `SwipeToDeleteContainer` takes a `resetSignal: Flow<Unit>`; a signal while the row is swiped away returns it to rest. `DashboardViewModel.deleteFailures` and `ListDetailViewModel.itemDeleteFailures` are one-shot events (not state, so a repeated failure for the same id signals again and cannot be conflated) emitted when a delete fails; the three screen call sites feed the matching id into the row. If `enabled` is false when the row settles, the container now resets instead of leaving it swiped. Finding: in Material3 as used here, disabling a row mid-swipe or during the settle animation already returns it to rest (the dismiss anchor is removed), so those tests pass with or without the new `reset()`; it stays as a guard for the one-frame window after settling, which a test cannot trigger. The failed-delete case is the real bug: the screen-level tests fail without the wiring and pass with it. Tests added: container (Android instrumented and iOS `compose.uiTest`: reset signal, disabled mid-swipe, disabled while settling), ViewModel failure-signal tests, and Dashboard and ListDetail screen tests with a failing repository. Verified: Android Debug/Release unit tests, iOS simulator tests, full instrumented suite against the local emulators (106 tests, 0 failures).
- 2026-10-06: Closed as DONE without the manual emulator/simulator check. A delete failure cannot be forced easily (Firestore queues writes while offline instead of failing them, so turning the network off does not do it); the automated tests above, including the screen-level failed-delete tests, are accepted as sufficient.

---

## PM-03 — Move iOS test harnesses out of the shipping app

**Priority:** Medium · **Status:** TODO · **Source:** FB-103-NB3, FB-307-NB1

**Problem (checked in code 2026-10-06).** About 2,060 lines of evidence harnesses still
compile into the ordinary iOS framework from `iosMain`:
`IosFirestoreListIntegrationCheck`, `IosFirestoreItemIntegrationCheck`,
`IosFirestoreCrossClientIntegrationCheck`, `IosPhotoStorageIntegrationCheck` and
`IosDashboardListenerCrashSelfCheck`. Eight launch-argument hooks that run them are called
from `AppDelegate` in `iosApp/iosApp/FirebaseBootstrap.swift` (lines ~340–347) **outside**
`#if FLUXIT_PARITY`. They are not exploitable (each needs a launch argument and refuses to
run unless emulator mode is on), but they are test code inside the shipping app.
`IosPhotoStorageIntegrationCheck`'s cross-device harness also has a known re-run bug
(FB-307-NB1: it claims to be idempotent but picks a stale item by `maxByOrNull` over
non-monotonic auto-IDs).

**Approach.** Reuse the existing gated pattern: `composeApp/src/firebaseParityIos` is only
compiled with `-Pfluxit.parity.enabled=true` (`composeApp/build.gradle.kts:127-131`), and
`IosAuthIntegrationCheck` already lives there behind `#if FLUXIT_PARITY`. Move the five
Kotlin checks into that source set (or delete the ones no longer worth keeping) and wrap
their Swift hooks in `#if FLUXIT_PARITY`. Check `iosTest` and other `iosMain` files for
references first. If `IosPhotoStorageIntegrationCheck` is kept, fix or soften FB-307-NB1.

**Done when:**
- [ ] The ordinary (non-parity) iOS framework contains no `*IntegrationCheck`/`*SelfCheck` classes.
- [ ] `AppDelegate` calls no harness hook outside `#if FLUXIT_PARITY`.
- [ ] The parity build still compiles and runs the checks that were kept.
- [ ] The ordinary iOS Debug and Release builds and the iOS simulator tests pass.
- [ ] FB-307-NB1 is fixed, or the harness is deleted.

**Log:**

---

## PM-04 — Photo upload error contract for direct callers

**Priority:** Low · **Status:** TODO · **Source:** FB-602-NB1

**Problem.** `PhotoStorage.uploadPhoto` documents that failures surface as
`PhotoStorageException`, but `validatePhotoSource` runs before the adapter's try/catch on
both platforms and can throw `PhotoRejected` for a caller passing invalid bytes directly.
Today's only caller (`ItemDetailViewModel`) validates first via `preparePhotoForUpload`, so
no user flow is affected.

**Approach.** Either document `PhotoRejected` as part of the contract, or wrap validation
so it surfaces as `PhotoStorageException`. Pick one and test it.

**Done when:**
- [ ] The contract KDoc and the behaviour agree on both platforms.
- [ ] A test covers a direct invalid-byte call.

**Log:**

---

## PM-05 — Test coverage gaps

**Priority:** Low · **Status:** TODO · **Source:** FB-007-NB1, FB-304-NB2, FB-105-NB2

Independent small tasks; tick them off separately.

- [ ] **FB-007-NB1:** add an `iosTest` asserting `IosFirebaseEmulatorSettings` matches `FirebaseEmulatorConfig` (guards against Android's `10.0.2.2` host translation leaking into iOS).
- [ ] **FB-304-NB2:** add an Android on-device boundary-value test for the real `BitmapFactory`-backed `ImageTransform.android.kt` (exact size limit, unsupported type, corrupt image). iOS already has `ImageTransformIosTest`.
- [ ] **FB-105-NB2:** add a Compose UI test for the account-switch path in `SessionGate.kt`, where `remember(...) { scopedStores.ownerFor(...) }` clears the old `ViewModelStore` during composition. A Compose UI test setup now exists in `iosTest` (FB-710).

**Log:**

---

## PM-06 — Session-restore outcome contract

**Priority:** Low · **Status:** TODO · **Source:** FB-104-NB1

**Problem.** `AuthRepository.restoreSession()` returns `Unit`
(`commonMain/.../domain/auth/AuthRepository.kt:41`), so `SessionGateViewModel` can only
observe the result through the `session` flow and uses `yield()` to wait for it. This
narrows, but does not provably close, a window where a late emission could be read as the
outcome. Both current adapters publish synchronously before returning, so there is no
actual bug today; the risk applies to a future adapter.

**Approach.** Make `restoreSession()` return its resolved session and use that value in
`SessionGateViewModel`, removing the `yield()` workaround. Do it only if you touch the auth
contract for another reason, or close it as `WONTFIX`.

**Done when:**
- [ ] `restoreSession()` returns its outcome; both adapters and the shared fakes are updated.
- [ ] `SessionGateViewModel` no longer relies on `yield()` ordering; its tests pass.

**Log:**

---

## PM-07 — Continuous integration (optional)

**Priority:** Optional · **Status:** TODO · **Source:** FB-206-NB2

The repository has no CI (`.github/workflows` does not exist). A first workflow could run
the Android unit tests (Debug/Release), the Rules/config tests and the Cloud Functions tests
on pull requests. The emulator-backed and iOS simulator suites are heavier; FB-206-NB2 notes
that the reconnect tests use real wall-clock timeouts (up to 30 s) and may be flaky in CI.
Firebase mobile config files are gitignored, so CI needs a strategy for them (for example
the emulator-only demo project, or encrypted secrets). Never commit real config or keys.

**Done when:**
- [ ] A workflow runs at least the Android unit tests on pull requests to `main`.
- [ ] No Firebase config or credential is committed.

**Log:**

---

## PM-08 — Remove migration-tracking references from code and docs

**Priority:** Last · **Status:** TODO · **Depends on:** PM-01…PM-07 `DONE` or `WONTFIX`

Once the follow-ups are finished, the migration bookkeeping is no longer needed in the
codebase.

**Scope (measured 2026-10-06 with `git grep -E '(FB|PLAN|DEC|MAN)-[0-9]{3}'`):**
- Code: 118 files / 916 lines under `composeApp/` (`commonMain` 22 files, `iosMain` 20, `androidMain` 13, tests 44, parity/debug 9, `build.gradle.kts`), `iosApp/iosApp/` (6 Swift files) and `functions/` (3).
- Config: `gradle.properties` (4 lines), `firestore.rules` (1), `storage.rules` (1).
- Docs: `README.md` (8 lines), `firebase/README.md` (46).
- Migration documents: `FIREBASE_MIGRATION_PLAN.md`, `FIREBASE_MIGRATION_STATUS.md`, `FIREBASE_BASELINE_FB-000.md`, the per-task evidence under `firebase/` (`FB-*-PROCEDURE.md`, `FB-*-RESULTS.md`, `FB-706-MANUAL.md` and the `security/`, `parity/`, `cutover/`, `room-removal/`, `photo-removal/`, `session-cleanup/`, `final-verification/` folders), `docs/firebase-migration/agent-config/` and this page.

**Guidelines:**
- Remove the ID, keep the reason. Many comments explain *why* code is shaped a certain way (for example the iOS `isSSLEnabled = false` emulator fix); rewrite them without the task ID instead of deleting them.
- The uppercase pattern misses lowercase identifiers such as `fb709…`, `fb710…`, `fb206…` (test fixtures, account prefixes, tags); also search `git grep -iE 'fb-?[0-9]{3}'`. Rename them only where it improves clarity.
- Several evidence scripts (`firebase/*/verify.py`, `evidence.json`) check exact source-file hashes. Editing comments breaks them, so delete or archive those scripts in the same pass rather than "fixing" them.
- Decide whether to delete the migration documents or keep a short summary (for example `docs/firebase-migration/SUMMARY.md` with decisions, architecture and rollback points). Git history keeps the full record either way; tag the last commit before deletion (for example `firebase-migration-record`) so it is easy to find.
- Keep the useful operational content of `firebase/README.md` (setup, emulators, deployment), minus the task IDs.

**Done when:**
- [ ] `git grep -E '(FB|PLAN|DEC|MAN)-[0-9]{3}'` and `git grep -iE 'fb-?[0-9]{3}'` return nothing outside the kept archive/summary (if any).
- [ ] Hash-pinned evidence scripts are removed or archived.
- [ ] All builds and test suites pass on Android and iOS.
- [ ] `README.md` and `firebase/README.md` read as normal project documentation.

**Log:**

---

## Not tracked as work

**Production-only (DEC-012 waived production).** Revisit these only if a production
environment is ever created:
- **FB-601-NB1 / FB-204-NB2:** item counters (`totalItems`, `completedItems`) are not
  server-authoritative. An owner can inflate their own counters within the per-write bound,
  and decrements are not clamped at zero. This does not give access to other users' data.
  Decide on server-side counters or a reconciliation job before production.
- **FB-603-NB1, FB-608-NB1, FB-504-NB2:** limits on what was verified (native-cloud and
  backend-cloud query tiers not run, deployed revision IDs not recorded, live aged-photo
  deletion not observed). They become production verification steps; there is nothing to code.

**Accepted as permanent notes (no action).** FB-007-NB3, FB-103-NB2, FB-105-NB3,
FB-205-NB1, FB-206-NB1, FB-407-NB2, FB-408-NB1, FB-409-NB2, FB-707-NB1, FB-710-NB4. They
record evidence limits or design facts in the status file and need no code change.
