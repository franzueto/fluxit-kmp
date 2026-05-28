# Phase 04 — Domain Layer

> **Goal:** Define the pure-Kotlin business core of FluxIt: entities, value objects, repository interfaces (consumed by Phase 03), platform ports (consumed by Phase 06), use cases (consumed by Phase 05), validation rules, and the unified error model. **Zero dependencies on Android, iOS, SQLDelight, Compose, or Koin runtime.**

**Owner:** Mobile platform
**Depends on:** Phase 01 (`:shared:domain` module exists), Phase 03 (DB shape locked — keeps domain entities aligned without coupling).
**Blocks:** Phase 05 (State), Phase 06 (Platform ports implemented), all feature phases.
**Exit criteria (Definition of Done):**
- `:shared:domain` builds for `commonMain` only — no `androidMain` or `iosMain` source set exists.
- Konsist enforces: no imports from `app.cash.sqldelight.*`, `android.*`, `platform.*` (iOS), `androidx.*`, `org.koin.core.*`, `kotlinx.coroutines.GlobalScope`, `kotlinx.coroutines.runBlocking`.
- Every use case has a unit test on the JVM target with 100% branch coverage of validation paths.
- All public types are `Stable`/value-like (data classes, value classes, sealed hierarchies) — no mutable state in domain.
- `./gradlew :shared:domain:allTests` runs in < 2s on a warm cache.

---

## 1. Module wiring

- [x] `:shared:domain/build.gradle.kts` applies `fluxit.kmp.library` with `commonMain` only. _Phase 03 carry-forward._
- [x] Dependencies (commonMain): `kotlinx-coroutines-core` (for `Flow` and `suspend`), `kotlinx-datetime`, `kotlinx-serialization-core` (for `RecurrenceRule` only — no JSON impl), Kermit (logging at this layer is acceptable; allows use cases to log decisions). _Kermit deferred to the slice that lands `AppLogger` (§5) — no domain code imports it yet, no reason to add the dep ahead of use._
- [x] No dependency on `:shared:data`, `:platform-*`, `:core-designsystem`. Verified by Konsist. _Slice 2 (2026-05-28): `:build-logic:test` `ArchitectureTest` extended to ban `android.*`, `androidx.*`, `platform.UIKit`, `platform.Foundation`, `dev.franzueto.fluxit.platform.*`, `app.cash.sqldelight.*`, `org.koin.core.*`, `dev.franzueto.fluxit.core.designsystem.*` (ADR-007a), `arrow.*` (ADR-007), and qualified-code uses of `kotlin.Result` (ADR-007)._
- [ ] Source layout:
  ```
  :shared:domain/src/commonMain/kotlin/com/fluxit/domain/
    model/        ← entities + value objects
    repository/   ← repository interfaces
    port/         ← platform capability interfaces
    error/        ← DataError, ValidationError sealed hierarchies
    usecase/      ← one file per use case
    rule/         ← pure business-rule helpers (e.g. CompletionCalculator)
  ```

## 2. Value objects (typed IDs + small primitives)

Use Kotlin `value class` for zero-cost wrappers; prevents passing an `ItemId` where a `ListId` is expected.

- [ ] `value class ListId(val raw: String)` + `companion object { fun new(idGen: IdGenerator) }`
- [ ] `value class ItemId(val raw: String)`
- [ ] `value class ReminderId(val raw: String)`
- [ ] `value class PhotoId(val raw: String)`
- [ ] `value class RelativePath(val raw: String)` — for photo storage paths
- [ ] `value class TrimmedNonBlank private constructor(val value: String)` with `Companion.of(raw: String): Result<TrimmedNonBlank, ValidationError>` — used by `ListDraft.name`, `ItemDraft.title`. Centralizes the "non-empty after trim" rule.
- [ ] `enum class ColorToken { PRIMARY_BLUE, ACCENT_ROSE, ACCENT_EMERALD, ACCENT_ORANGE, ACCENT_INDIGO, ACCENT_SKY }` — mirrors the Create List swatch palette from Phase 02. Domain owns this enum (not designsystem) because it's part of the *product model* a list "is colored X".
- [ ] `enum class FluxItIconRef { CART, HOME, BRIEFCASE, PLANE, FORK_KNIFE, DUMBBELL, STAR, MORE }` — the 8 icon-picker choices from the Create List screen. Same rationale as `ColorToken`. **Note:** Phase 03 referenced `IconNameAdapter` against the designsystem-generated enum — reconcile by having designsystem *consume* this domain enum to drive icon registration, **not** the other way around. ADR-006c is updated/superseded — see ADR-007a in §10.

## 3. Entities (immutable data classes)

Distinct from DB rows: domain entities expose the shapes the rest of the app reasons about, with `kotlinx.datetime.Instant` for time and value-class IDs.

- [ ] **`ListSummary`** — for the dashboard.
  ```
  data class ListSummary(
      id: ListId, name: String, icon: FluxItIconRef, color: ColorToken,
      isStarred: Boolean,
      totalItems: Int, completedItems: Int,
      lastActivityAt: Instant, createdAt: Instant
  ) {
      val completionFraction: Float get() = if (totalItems == 0) 0f else completedItems.toFloat() / totalItems
      val isFullyComplete: Boolean get() = totalItems > 0 && completedItems == totalItems
  }
  ```
- [ ] **`ListDetail`** — full list metadata + reminder hint (`hasActiveReminder: Boolean`); items live in a separate flow.
- [ ] **`Item`** — `id, listId, title, subtitle?, description?, isCompleted, isStarred, photoId?, createdAt, updatedAt`.
- [ ] **`ItemsSection`** — `data class ItemsSection(active: List<Item>, completed: List<Item>) { val total = active.size + completed.size; val completedCount = completed.size }`. The dual-list shape that backs the `TO BUY` / `COMPLETED` UI sections directly.
- [ ] **`Reminder`** — `id, owner: ReminderOwner, firesAt: Instant, recurrence: RecurrenceRule, isActive: Boolean`.
- [ ] **`sealed class ReminderOwner { data class List(val id: ListId); data class Item(val id: ItemId) }`** — replaces stringly-typed owner_type/owner_id at the domain edge.
- [ ] **`sealed class RecurrenceRule`** — `None`, `Daily`, `Weekly(daysOfWeek: Set<DayOfWeek>)`, `Monthly(dayOfMonth: Int)`. Marked `@Serializable` — JSON impl injected at the data boundary.
- [ ] **`Photo`** — `id, relativePath: RelativePath, mime, widthPx, heightPx, byteSize, createdAt`.

### Drafts (write-side payloads)

- [ ] **`ListDraft`** — `name: TrimmedNonBlank, icon: FluxItIconRef, color: ColorToken, reminder: ReminderSpec?` (reminder optional from the Create-List screen's "Reminder Settings" entry).
- [ ] **`ItemDraft`** — `title: TrimmedNonBlank, subtitle: String? = null` (the inline composer only collects a title; subtitle/description set later via Edit Item).
- [ ] **`ItemPatch`** — `data class ItemPatch(title: TrimmedNonBlank? = null, subtitle: Optional<String> = Unset, description: Optional<String> = Unset, photoId: Optional<PhotoId?> = Unset)` where `Optional` is a tiny domain-owned `sealed interface { data object Unset; data class Set<T>(val v: T) }` — distinguishes "don't touch" from "set to null" without `null`-vs-absent ambiguity.
- [ ] **`ReminderSpec`** — `data class ReminderSpec(owner: ReminderOwner, firesAt: Instant, recurrence: RecurrenceRule = None)`.

## 4. Repository interfaces (formal — Phase 03 implements these)

Re-declared here as the canonical signatures. Each method's contract is documented inline (KDoc) including: which `DataError` variants it can return, ordering guarantees, and whether the returned `Flow` ever completes.

- [ ] `ListsRepository` — methods enumerated in Phase 03 §5; KDoc in code.
- [ ] `ItemsRepository` — same.
- [ ] `RemindersRepository` — same; KDoc clarifies that `schedule` only persists; platform scheduling happens via the `ReminderScheduler` port.
- [ ] `PhotosRepository` — same; KDoc clarifies the file-first-then-row crash-safety contract.
- [ ] **All `Flow` returns are documented as cold + conflated** by convention; consumers `distinctUntilChanged()` themselves only when they need pre-mapping equality.

## 5. Platform ports (Phase 06 implements these)

Domain-owned interfaces for capabilities that have to live per-platform. This is the seam that keeps domain pure.

- [ ] **`Clock`** — `fun now(): Instant`. Production binds to `kotlinx.datetime.Clock.System`; tests inject `FakeClock(initial: Instant)` with `advanceBy(Duration)`.
- [ ] **`IdGenerator`** — `fun newId(): String`. Production: UUIDv4 per Phase 03 §9; tests: deterministic counter.
- [ ] **`ReminderScheduler`**
  - `suspend fun schedule(reminder: Reminder): Result<PlatformHandle, SchedulerError>`
  - `suspend fun cancel(handle: PlatformHandle): Result<Unit, SchedulerError>`
  - `suspend fun rescheduleAll(active: List<Reminder>): Result<Unit, SchedulerError>` — used on app start / boot-completed (Android) to repopulate the OS-level schedule.
  - `value class PlatformHandle(val raw: String)`.
  - `sealed class SchedulerError { object PermissionDenied; object SystemBusy; data class Unknown(val cause: Throwable?) }`.
- [ ] **`PhotoStorage`** — defined in Phase 03 §7; redeclared here authoritatively.
- [ ] **`PhotoCapture`**
  - `suspend fun capture(): Result<CapturedPhoto, CaptureError>` — opens camera UI.
  - `suspend fun pickFromLibrary(): Result<CapturedPhoto, CaptureError>` — opens system picker.
  - `data class CapturedPhoto(bytes: ByteArray, mime: String, widthPx: Int, heightPx: Int)`.
  - `sealed class CaptureError { object PermissionDenied; object UserCancelled; data class Unknown(cause: Throwable?) }`.
- [ ] **`AppLogger`** — thin facade over Kermit so domain doesn't `import co.touchlab.kermit.*` directly. Methods: `d/i/w/e(tag, message, cause?)`.
- [ ] **`AnalyticsSink`** — `fun track(event: AnalyticsEvent)` where `AnalyticsEvent` is a sealed hierarchy (`ListCreated`, `ItemAdded`, `ItemCompleted`, `ReminderScheduled`, `PhotoAttached`, …). Domain emits *typed* events; per-platform impl flattens to vendor-specific schemas.
- [ ] **`ConfigProvider`** — `fun <T> get(key: ConfigKey<T>): T`. Backs feature flags / build-config (e.g. `Calendar.enabled = false` per ADR-004).

## 6. Error model

Single sealed hierarchy per concern; all use cases return `Result<T, DomainError>` where `DomainError` is the union.

- [ ] **`sealed class DomainError`**
  - `data class Validation(val field: String, val rule: ValidationError)` — never thrown; always returned.
  - `data class NotFound(val entity: String, val id: String)`
  - `data class Conflict(val message: String)` — e.g. trying to add an item to a deleted list.
  - `data class StorageFailure(val cause: Throwable?)` — wraps `DataError.Storage`.
  - `data class SchedulerFailure(val reason: SchedulerError)`
  - `data class CaptureFailure(val reason: CaptureError)`
- [ ] **`sealed class ValidationError`** — `Empty`, `TooLong(max: Int)`, `OutOfRange(min, max)`, `InvalidFormat`. Used by `TrimmedNonBlank.of` and the `ReminderSpec` validator.
- [ ] **`Result<T, E>`** — adopt `kotlin.Result<T>`? **No** — its error is fixed to `Throwable`, which loses type information. Use a tiny in-house `sealed interface Outcome<out T, out E> { Success(value: T); Failure(error: E) }` with `map`, `flatMap`, `mapError`, `fold`. (Name: `Outcome` to avoid collision with `kotlin.Result`.) Lives in `:shared:domain` and is re-exported from `:core:core-utils` for non-domain callers.
- [ ] Every `DataError` from Phase 03 maps to a `DomainError` via a single extension function `DataError.toDomain(): DomainError`. Tested.

## 7. Use cases

Each use case is a small class with `operator fun invoke(...)` (or `suspend operator fun invoke`), constructor-injected dependencies, no shared mutable state. **Group by feature**, not by entity, so feature phases can grep by directory.

### Lists

- [ ] **`ObserveLists`** — `operator fun invoke(): Flow<List<ListSummary>>` — debounce/sort applied here, not in repo.
- [ ] **`SearchLists`** — `operator fun invoke(query: String): Flow<List<ListSummary>>`. Empty query → all. Trims, lower-cases, applies `query.length >= 1` rule.
- [ ] **`CreateList`** — validates draft → `repo.create` → if `draft.reminder != null` then `ScheduleReminder` → emits `AnalyticsEvent.ListCreated`.
- [ ] **`UpdateListAppearance`** — change icon/color, validates color is in palette.
- [ ] **`RenameList`**
- [ ] **`SetListStarred`**
- [ ] **`ReorderList`** — input is `(movedId, beforeId?, afterId?)`; computes new fractional sort_order in pure code, calls `repo.reorder`.
- [ ] **`DeleteList`** — soft-deletes list AND cancels all active reminders for it. Returns `Outcome<DeletedListSummary, DomainError>` so UI can offer undo (per Phase 03 open question — supports either UX).
- [ ] **`UndoDeleteList`** — restores `deleted_at = NULL`; reschedules reminders; only valid within an undo window the *state* layer enforces (domain doesn't know about wall-clock undo windows beyond accepting the call).

### Items

- [ ] **`ObserveListDetail`** — combines `ListsRepository.observe(id)` + `ItemsRepository.observeByList(id)` into one stream of `(detail, sections)`. Uses `kotlinx.coroutines.flow.combine`.
- [ ] **`AddItem`** — validates title; appends to active section.
- [ ] **`ToggleItemCompleted`** — `setCompleted(id, !current)`. Single use case (no separate `Complete`/`Uncomplete`).
- [ ] **`UpdateItemDetails`** — backs the Edit Item screen (title, description, photo).
- [ ] **`AttachPhotoToItem`** — orchestrates `PhotoCapture` (or `pickFromLibrary`) → optional re-encode hook → `PhotosRepository.ingest` → `ItemsRepository.update(itemId, photoPatch)`. Returns `Outcome<PhotoId, DomainError>`.
- [ ] **`DetachPhotoFromItem`** — clears `photo_id`, schedules `PhotoJanitor` to GC the file later.
- [ ] **`ReorderItem`**
- [ ] **`SetItemStarred`**
- [ ] **`DeleteItem`** + **`UndoDeleteItem`**
- [ ] **`ClearCompletedItems`** — bulk soft-delete. Returns `Outcome<List<ItemId>, DomainError>` (the deleted ids) so the state layer can back a single bulk-undo snackbar (per Phase 08 resolution). Implementation calls `ItemsRepository.clearCompleted(listId)` which surfaces `RETURNING id` rows from Phase 03.
- [ ] **`RestoreItems`** — `suspend operator fun invoke(ids: List<ItemId>): Outcome<Unit, DomainError>`. Bulk reverse of `ClearCompletedItems`: sets `deleted_at = NULL` for each id in a single transaction; idempotent (already-active items are no-ops). Also used by the bulk-undo path of any future v2 "select multiple → delete" UX.

### Reminders

- [ ] **`ScheduleReminder`** — validates `firesAt > Clock.now()`; persists row → calls `ReminderScheduler.schedule` → writes back the `PlatformHandle` via `RemindersRepository.rebindPlatformHandle`. If platform schedule fails with `PermissionDenied`, row stays with `is_active = 0` and `platform_handle = NULL`; surfaces `SchedulerFailure(PermissionDenied)` so UI can prompt for permission and retry.
- [ ] **`CancelReminder`** — cancels at platform first, then DB. Idempotent.
- [ ] **`RehydrateReminders`** — runs on app start: `RemindersRepository.selectActive` → `ReminderScheduler.rescheduleAll`. Handles boot-completed (Android) and cold-start (iOS) consistently.
- [ ] **`ObserveRemindersForList`** / **`ObserveRemindersForItem`**.

### Photos / housekeeping

- [ ] **`PhotoJanitor`** — `selectOrphaned(olderThan = 24h)` → for each: `PhotoStorage.delete` → `PhotosRepository.deleteIfOrphaned`. Run on app start and after `DetachPhotoFromItem`.

### App-level

- [ ] **`InitializeApp`** — composite use case run once at startup: `RehydrateReminders` + `PhotoJanitor`. Returns `Flow<InitProgress>` so the splash/state layer can show progress (or just complete silently in v1).

## 8. Pure business rules

Helpers with no IO; testable by themselves.

- [ ] **`CompletionCalculator`** — given `(total, completed)`, returns the progress bar fraction and the `13/20` string. Centralized so Phase 08 doesn't reinvent it.
- [ ] **`SortOrderArithmetic`** — `between(prev: Double?, next: Double?): Double` and `needsCompaction(orders: List<Double>): Boolean`. Property-based test: 1000 random reorders never collapse without compaction.
- [ ] **`RecurrenceCalculator`** — `nextFireAfter(rule: RecurrenceRule, after: Instant, tz: TimeZone): Instant?`. Used by `ReminderScheduler` re-arming logic. v1 scope locked (Phase 09): `None / Daily / Weekly(daysOfWeek) / Monthly(dayOfMonth)`. Required behaviors:
  - `None` → returns `null` (no next fire).
  - `Daily` → `after + 24h` snapped to the original wall-clock time in `tz` (handles DST transitions: spring-forward skips, fall-back uses first occurrence).
  - `Weekly(days)` → next instant whose day-of-week ∈ `days`, at the original wall-clock time.
  - `Monthly(dayOfMonth)` → same day-of-month next month at original wall-clock time. **Edge case**: if `dayOfMonth = 31` and target month has 30 (or Feb), clamp to last day of that month (e.g. Jan 31 → Feb 28/29 → Mar 31). Documented in KDoc + property test.
  - All variants: result is always strictly `> after` (no infinite loops on equal instants).
- [ ] Property tests: `(rule, after, tz) → next` always advances time; iterating 1000 times for Daily/Weekly/Monthly never produces non-monotonic sequences; Monthly clamping behaves correctly across full year cycles in `Europe/London` (DST), `Asia/Kolkata` (no DST, +5:30 offset), `Pacific/Apia` (DST + dateline shift) test zones.
- [ ] **`PaletteCatalog`** — single source for the available `ColorToken` and `FluxItIconRef` values surfaced in the Create List picker. Avoids "what icons can the user pick?" being answered in three places.

## 9. Concurrency contract

- [ ] All `suspend` boundaries declare *what dispatcher they require*: `// Caller dispatcher: any. This use case does not block.` Annotated via KDoc, not enforced (annotation framework would be overkill).
- [ ] Use cases never call `withContext(Dispatchers.IO)` — that's the data layer's responsibility (SQLDelight driver decides). Domain stays dispatcher-agnostic.
- [ ] No `Flow.shareIn` / `stateIn` in domain — those are state-layer choices.
- [ ] Cancellation: every use case is structured-concurrency-clean. Verified by a Konsist rule banning `GlobalScope` in this module.

## 10. ADRs to write in this phase

- [ ] **ADR-007** — In-house `Outcome<T, E>` type vs. `kotlin.Result<T>` vs. Arrow `Either`. Why we picked our own: typed errors, no Arrow buy-in, no kotlin.Result `Throwable` ceiling. _Drafted Proposed 2026-05-28 (Slice 1); flips Accepted on §13 hand-off once every use case returns `Outcome<T, E>` and Konsist bans `kotlin.Result` / `arrow.core.*` in `:shared:domain`._
- [x] **ADR-007a** — Domain owns `ColorToken` and `FluxItIconRef`; designsystem consumes them. **Supersedes ADR-006c** (which had data depending on designsystem). _Accepted 2026-05-28 (Slice 1); implementation shipped in Phase 03 §3, ADR ratifies the as-built state._
- [ ] **ADR-007b** — Use-case shape: small classes with `operator fun invoke` (vs. top-level suspend functions vs. interactors with multiple methods). Why classes: DI clarity, testability, KDoc placement, ability to swap impls in tests. _Drafted Proposed 2026-05-28 (Slice 1); flips Accepted on §13 hand-off once three use cases across two feature areas land in this shape and the Konsist "no top-level suspend fun in usecase/" rule is in place._

## 11. Testing

- [ ] **One test class per use case** — happy path, each validation branch, each `DomainError` it can produce. Uses fakes from `domain-test-fixtures` (a sibling `commonTest`-only fixture package, not a separate module yet).
- [ ] **Fakes** (in `commonTest`):
  - `FakeListsRepository`, `FakeItemsRepository`, `FakeRemindersRepository`, `FakePhotosRepository` — backed by in-memory `MutableStateFlow`s.
  - `FakeClock(initial)` with `advanceBy`.
  - `FakeIdGenerator(prefix = "id")` — yields `id-1, id-2, …`.
  - `FakeReminderScheduler` with controllable failure modes.
  - `RecordingAnalyticsSink` to assert event emission.
- [ ] **Property tests** for `SortOrderArithmetic` and `RecurrenceCalculator` (using Kotest property test integration).
- [ ] **Konsist tests** in `:shared:domain` test source:
  - No imports from forbidden packages (see exit criteria).
  - Every public class/function in `usecase/` has KDoc.
  - Every `DomainError` subclass is `data class` or `data object` (never raw `class`).

## 12. Open questions for this phase

- [ ] **Description length cap.** Edit Item description — enforce max length (e.g. 2000 chars)? Affects `ItemPatch.description` validation.
- [ ] **List name length cap.** 60 chars? 120? Mockup shows short names, but no spec.
- [ ] **Star semantics.** `is_starred` on list AND item per ADR-004 forward-compat — but does the v1 UI ever set them? If no, we can ship the schema and use cases but skip wiring set-star intents in feature phases (still tested via use case tests).
- [ ] **Undo window owner.** Domain accepts `UndoDelete*` calls regardless of timing; the *state* layer enforces "5s window expired" by simply not invoking undo. Confirm that's the right split.
- [ ] **Recurrence scope** (carried over from Phase 03 — affects `RecurrenceCalculator` test surface). Lock here.

## 13. Hand-off checklist (gate to Phase 05)

- [ ] All checkboxes above ✅.
- [ ] Konsist tests green; `:shared:domain` has zero forbidden imports.
- [ ] Use case branch coverage ≥ 95% (target 100%, tolerate 5% for trivial guards).
- [ ] `MASTER_PLAN.md`: Phase 04 → 🟢, ▶ Next Step → Phase 05.
- [ ] `00_DECISIONS.md`: ADR-007 (a/b) accepted; ADR-006c marked superseded by ADR-007a.

---

## Implementation log (chronological, for traceability across sessions)

- **2026-05-28** — Slice 2: §1 Konsist forbidden-imports test extended in
  `:build-logic`'s `ArchitectureTest`. The pre-existing "no Android/iOS"
  test grew into a Phase 04 §1 + ADR-007 + ADR-007a consolidated ban
  list: `app.cash.sqldelight.*`, `org.koin.core.*`,
  `dev.franzueto.fluxit.core.designsystem.*`, `arrow.*`, plus a tight
  regex (`kotlin\.Result[<.(]`) for qualified-code uses of
  `kotlin.Result` (KDoc mentions in `Outcome.kt` deliberately allowed —
  documenting *why* domain doesn't use it is fine). Test source sets
  exempted so commonTest can interop with platform Result-returning APIs.
  Phase 04 §1 rows 1–3 ticked; row 4 (source layout) stays open until
  the §3 / §5 / §7 slices populate `model/`, `port/`, `error/`,
  `usecase/`, `rule/`. _Commit `6f03845`._
- **2026-05-28** — Slice 1: ADR-007a Accepted (domain owns `ColorToken` +
  `FluxItIconRef`; supersedes ADR-006c) + ADR-007 and ADR-007b drafted as
  Proposed (Outcome type, use-case shape). Pending list renumbered: the
  Phase 05 MVI ADR moves from collision-spot "ADR-007" to ADR-014. ADR-006c
  Status pointer cleaned up to drop the "anticipated" qualifier. _Commit
  `52cf043`._
