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

- [x] `value class ListId(val raw: String)` + `companion object { fun new(idGen: IdGenerator) }`. _Phase 03 §5 carry-forward; `ListId.Companion.new(idGen)` factory added in Slice 5 (2026-05-28) alongside the §5 `IdGenerator` port typealias._
- [x] `value class ItemId(val raw: String)`. _Phase 03 §5 carry-forward._
- [x] `value class ReminderId(val raw: String)`. _Phase 03 §5 carry-forward._
- [x] `value class PhotoId(val raw: String)`. _Phase 03 §5 carry-forward._
- [x] `value class RelativePath(val raw: String)` — for photo storage paths. _Slice 3 (2026-05-28); non-blank `init` guard, path-shape validation deferred to the `PhotoStorage` impl at the data/platform boundary._
- [x] `value class TrimmedNonBlank private constructor(val value: String)` with `Companion.of(raw: String): Result<TrimmedNonBlank, ValidationError>` — used by `ListDraft.name`, `ItemDraft.title`. Centralizes the "non-empty after trim" rule. _Slice 3 (2026-05-28); factory returns `Outcome<TrimmedNonBlank, ValidationError>` (reconciled spelling per ADR-007), `maxLen: Int? = null` parameter for the optional cap (per-field caps land with the use-case slices that own them — §12 description/list-name length-cap rows stay open)._
- [x] `enum class ColorToken { PRIMARY_BLUE, ACCENT_ROSE, ACCENT_EMERALD, ACCENT_ORANGE, ACCENT_INDIGO, ACCENT_SKY }` — mirrors the Create List swatch palette from Phase 02. Domain owns this enum (not designsystem) because it's part of the *product model* a list "is colored X". _Phase 03 §3 carry-forward (ratified by ADR-007a Slice 1)._
- [x] `enum class FluxItIconRef { CART, HOME, BRIEFCASE, PLANE, FORK_KNIFE, DUMBBELL, STAR, MORE }` — the 8 icon-picker choices from the Create List screen. Same rationale as `ColorToken`. **Note:** Phase 03 referenced `IconNameAdapter` against the designsystem-generated enum — reconcile by having designsystem *consume* this domain enum to drive icon registration, **not** the other way around. ADR-006c is updated/superseded — see ADR-007a in §10. _Phase 03 §3 carry-forward (ratified by ADR-007a Slice 1)._

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
- [x] **`Item`** — `id, listId, title, subtitle?, description?, isCompleted, isStarred, photoId?, createdAt, updatedAt`. _Phase 03 §5 carry-forward._
- [x] **`ItemsSection`** — `data class ItemsSection(active: List<Item>, completed: List<Item>) { val total = active.size + completed.size; val completedCount = completed.size }`. The dual-list shape that backs the `TO BUY` / `COMPLETED` UI sections directly. _Phase 03 §5 carry-forward. Shipped with `total` and `completedCount` as explicit fields rather than computed properties (matches the single-query rollup from `selectByListGroupedByStatus`)._
- [x] **`Reminder`** — `id, owner: ReminderOwner, firesAt: Instant, recurrence: RecurrenceRule, isActive: Boolean`. _Phase 03 §5 carry-forward; also carries `platformHandle: String?`, `createdAt`, `updatedAt` so the data layer can round-trip the row without a separate persistence model._
- [x] **`sealed class ReminderOwner { data class List(val id: ListId); data class Item(val id: ItemId) }`** — replaces stringly-typed owner_type/owner_id at the domain edge. _Phase 03 §5 carry-forward; shipped as `sealed interface ReminderOwner` with `OfList(listId)` / `OfItem(itemId)` variants (the spec-shape; "List"/"Item" renamed to "OfList"/"OfItem" to avoid `kotlin.collections.List` name collision). `ReminderOwnerType` enum kept as a storage-side discriminator only — the data mapper converts `(ReminderOwnerType, String)` ↔ `ReminderOwner` at the SQL boundary._
- [x] **`sealed class RecurrenceRule`** — `None`, `Daily`, `Weekly(daysOfWeek: Set<DayOfWeek>)`, `Monthly(dayOfMonth: Int)`. Marked `@Serializable` — JSON impl injected at the data boundary. _Phase 03 §5 carry-forward; ships as `sealed class` with `@Serializable` annotations on the type and each variant. JSON format runtime lives in `:shared:data`._
- [x] **`Photo`** — `id, relativePath: RelativePath, mime, widthPx, heightPx, byteSize, createdAt`. _Phase 03 §5 carry-forward; shipped with `RelativePath` typed (Slice 3 added the value class — see §2)._

### Drafts (write-side payloads)

- [x] **`ListDraft`** — `name: TrimmedNonBlank, icon: FluxItIconRef, color: ColorToken, reminder: ReminderSpec?` (reminder optional from the Create-List screen's "Reminder Settings" entry). _Phase 03 §5 carry-forward shipped with `name: String` and no `reminder` field (data layer needed only the four columns). Re-typing `name` to `TrimmedNonBlank` and adding `reminder: ReminderSpec?` deferred to the `CreateList` use-case slice (§7) where the validator wrapping happens — keeping the data layer's draft contract stable until the use case actually orchestrates the validation + scheduling pair._
- [x] **`ItemDraft`** — `title: TrimmedNonBlank, subtitle: String? = null` (the inline composer only collects a title; subtitle/description set later via Edit Item). _Phase 03 §5 carry-forward shipped with `title: String` and `subtitle, description, photoId, isStarred` fields. Re-typing `title` to `TrimmedNonBlank` deferred to the `AddItem` use-case slice (§7), same rationale as `ListDraft`._
- [x] **`ItemPatch`** — `data class ItemPatch(title: TrimmedNonBlank? = null, subtitle: Optional<String> = Unset, description: Optional<String> = Unset, photoId: Optional<PhotoId?> = Unset)` where `Optional` is a tiny domain-owned `sealed interface { data object Unset; data class Set<T>(val v: T) }` — distinguishes "don't touch" from "set to null" without `null`-vs-absent ambiguity. _**Slice 4 reconciliation (2026-05-28, Option A):** `ItemPatch` stays a **full-replacement payload** at the data edge — the shape shipped in Phase 03 §5 (`title, subtitle, description, photoId` all required) is the right shape for the atomic SQL UPDATE in `Items.sq`. The `Optional<T>` / "don't touch vs. set to null" concept moves to the **use-case API** (`UpdateItemDetails` in §7), which reads the current item, applies the partial intent, and emits a complete `ItemPatch` to the repo. `Optional<T>` therefore lives in §6 as a use-case parameter-shape primitive (introduced in the §7 slice that needs it first), **not** as a domain type that ItemPatch carries. Title re-typing to `TrimmedNonBlank` follows §3 `ItemDraft` — deferred to the §7 use-case slice that does the validation._
- [x] **`ReminderSpec`** — `data class ReminderSpec(owner: ReminderOwner, firesAt: Instant, recurrence: RecurrenceRule = None)`. _Phase 03 §5 carry-forward — shipped exactly as spec'd._

## 4. Repository interfaces (formal — Phase 03 implements these)

Re-declared here as the canonical signatures. Each method's contract is documented inline (KDoc) including: which `DataError` variants it can return, ordering guarantees, and whether the returned `Flow` ever completes.

- [ ] `ListsRepository` — methods enumerated in Phase 03 §5; KDoc in code.
- [ ] `ItemsRepository` — same.
- [ ] `RemindersRepository` — same; KDoc clarifies that `schedule` only persists; platform scheduling happens via the `ReminderScheduler` port.
- [ ] `PhotosRepository` — same; KDoc clarifies the file-first-then-row crash-safety contract.
- [ ] **All `Flow` returns are documented as cold + conflated** by convention; consumers `distinctUntilChanged()` themselves only when they need pre-mapping equality.

## 5. Platform ports (Phase 06 implements these)

Domain-owned interfaces for capabilities that have to live per-platform. This is the seam that keeps domain pure.

- [x] **`Clock`** — `fun now(): Instant`. Production binds to `kotlinx.datetime.Clock.System`; tests inject `FakeClock(initial: Instant)` with `advanceBy(Duration)`. _Slice 5 (2026-05-28); shipped as `fun interface Clock` with a `companion object { val System: Clock = Clock { kotlinx.datetime.Clock.System.now() } }` binding. The richer `FakeClock(initial, advanceBy(Duration))` fixture lands with the slice that first needs time-advance semantics (likely `RecurrenceCalculator` / `ScheduleReminder`); Slice 5 covers the seam with inline `Clock { fixed }` lambdas in tests._
- [x] **`IdGenerator`** — `fun newId(): String`. Production: UUIDv4 per Phase 03 §9; tests: deterministic counter. _Slice 5 (2026-05-28); shipped as a typealias in `:shared:domain/port/IdGenerator.kt` pointing at `dev.franzueto.fluxit.core.utils.IdGenerator` (per ADR-006a — the seam physically lives in `:core:core-utils` to avoid the `:shared:data` → `:shared:domain` cycle). Use cases import from `dev.franzueto.fluxit.shared.domain.port` so the §5 port surface stays coherent. `:shared:domain/build.gradle.kts` gained `implementation(project(":core:core-utils"))`; Konsist test (Slice 2) does not ban this edge (`:core:core-utils` is platform-neutral primitives, not designsystem)._
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

- [x] **`sealed class DomainError`**
  - `data class Validation(val field: String, val rule: ValidationError)` — never thrown; always returned.
  - `data class NotFound(val entity: String, val id: String)`
  - `data class Conflict(val message: String)` — e.g. trying to add an item to a deleted list.
  - `data class StorageFailure(val cause: Throwable?)` — wraps `DataError.Storage`.
  - `data class SchedulerFailure(val reason: SchedulerError)`
  - `data class CaptureFailure(val reason: CaptureError)`
  _Slice 6 (2026-05-28): four variants shipped (`Validation`, `NotFound`, `Conflict`, `StorageFailure`). `SchedulerFailure` / `CaptureFailure` deferred to the slices that introduce the `ReminderScheduler` / `PhotoCapture` ports respectively — adding them now would require placeholder `SchedulerError` / `CaptureError` types that the consuming ports would then have to reshape. KDoc on the sealed class names the two deferred variants so future readers see the gap is intentional._
- [x] **`sealed class ValidationError`** — `Empty`, `TooLong(max: Int)`, `OutOfRange(min, max)`, `InvalidFormat`. Used by `TrimmedNonBlank.of` and the `ReminderSpec` validator. _Slice 3 (2026-05-28) seeded `Empty`, `TooLong(max)`, `InvalidFormat`. `OutOfRange(min, max)` lands with the `ReminderSpec` validator slice (likely §7 `ScheduleReminder` or §8 `RecurrenceCalculator`) that first needs it — keeping the sum closed but growing on demand._
- [x] **`Result<T, E>`** — adopt `kotlin.Result<T>`? **No** — its error is fixed to `Throwable`, which loses type information. Use a tiny in-house `sealed interface Outcome<out T, out E> { Success(value: T); Failure(error: E) }` with `map`, `flatMap`, `mapError`, `fold`. (Name: `Outcome` to avoid collision with `kotlin.Result`.) Lives in `:shared:domain` and is re-exported from `:core:core-utils` for non-domain callers. _Phase 03 §5 shipped `Outcome<out T, out E>` with `Ok`/`Err` constructors (reconciled spelling per ADR-007) and `map` / `flatMap`. Slice 6 (2026-05-28) added `mapError` and `fold` so the `repo.create(draft).mapError { it.toDomain(entity = "List") }` use-case pattern works. Re-export from `:core:core-utils` deferred to the slice that introduces the first non-domain consumer (likely Phase 05's state layer)._
- [x] Every `DataError` from Phase 03 maps to a `DomainError` via a single extension function `DataError.toDomain(): DomainError`. Tested. _Slice 6 (2026-05-28); shipped as `DataError.toDomain(entity: String = "unknown"): DomainError` so call sites supply the entity-type hint for `NotFound` (e.g. `it.toDomain(entity = "List")`). All five `DataError` variants covered with 6 unit tests including the design call-out that `DataError.Validation` (storage-side constraint violation) maps to `DomainError.Conflict`, **not** `DomainError.Validation` — the latter is reserved for use-case-edge input validators._

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

- [x] **`CompletionCalculator`** — given `(total, completed)`, returns the progress bar fraction and the `13/20` string. Centralized so Phase 08 doesn't reinvent it. _Slice 7 (2026-05-28); shipped as `object CompletionCalculator` with `fraction(total, completed): Float`, `display(total, completed): String`, `isFullyComplete(total, completed): Boolean`. All three apply `require(total >= 0)` + `require(completed in 0..total)` guards; empty lists return `0f` for fraction and `false` for isFullyComplete (a 0/0 list is not "complete" — matches dashboard intent)._
- [x] **`SortOrderArithmetic`** — `between(prev: Double?, next: Double?): Double` and `needsCompaction(orders: List<Double>): Boolean`. Property-based test: 1000 random reorders never collapse without compaction. _Slice 7 (2026-05-28); shipped as `object SortOrderArithmetic` with `SEED_SORT_ORDER = 1.0` + `REBALANCE_EPSILON = 1e-9` constants matching `SqlListsRepository`'s companion verbatim. The data layer duplicates this math inline today (Phase 03 §5/§8); migrating those impls to consume this helper is a mechanical follow-up tracked for whenever a §7 use case (e.g. `ReorderList` / `ReorderItem`) needs to compute the target in pure code before calling the repo. Property test: 1000 random inserts via `between()` produce midpoints strictly inside their brackets. Targeted test: 60 inserts always into the tightest gap trigger at least one compaction, with the post-compaction list passing `needsCompaction == false` (the random-inserts case doesn't concentrate depth enough to hit the 1e-9 threshold, so the compaction path needs its own targeted exercise — first attempt's `compactions >= 1` assertion on the random run failed and was split into the two tests above)._
- [x] **`RecurrenceCalculator`** — `nextFireAfter(rule: RecurrenceRule, after: Instant, tz: TimeZone): Instant?`. Used by `ReminderScheduler` re-arming logic. v1 scope locked (Phase 09): `None / Daily / Weekly(daysOfWeek) / Monthly(dayOfMonth)`. Required behaviors:
  - `None` → returns `null` (no next fire).
  - `Daily` → `after + 24h` snapped to the original wall-clock time in `tz` (handles DST transitions: spring-forward skips, fall-back uses first occurrence).
  - `Weekly(days)` → next instant whose day-of-week ∈ `days`, at the original wall-clock time.
  - `Monthly(dayOfMonth)` → same day-of-month next month at original wall-clock time. **Edge case**: if `dayOfMonth = 31` and target month has 30 (or Feb), clamp to last day of that month (e.g. Jan 31 → Feb 28/29 → Mar 31). Documented in KDoc + property test.
  - All variants: result is always strictly `> after` (no infinite loops on equal instants).
  _Slice 8 (2026-05-28); shipped as `object RecurrenceCalculator` with a single `findNext(after, tz, advance)` core loop that all three non-`None` variants share. The DST skip / fall-back resolution leverages kotlinx-datetime's `LocalDateTime.toInstant(tz)` policy (earlier-of-ambiguous for fall-back) + a wall-clock round-trip check that detects spring-forward gaps and advances another step. 400-iteration safety cap on the search loop._
- [x] Property tests: `(rule, after, tz) → next` always advances time; iterating 1000 times for Daily/Weekly/Monthly never produces non-monotonic sequences; Monthly clamping behaves correctly across full year cycles in `Europe/London` (DST), `Asia/Kolkata` (no DST, +5:30 offset), `Pacific/Apia` (DST + dateline shift) test zones. _Slice 8 (2026-05-28); three iteration property tests cover all three TZs (Daily across 365 iterations in London — both DST transitions; Monthly(31) for 24 months in Apia — dateline-shift edge; Weekly across 200 iterations in Kolkata — fixed +5:30). Each iteration asserts strict `next > current` monotonicity + per-variant invariants (Monthly's dayOfMonth ∈ 28..31; Weekly's dayOfWeek ∈ days). Plus 19 deterministic unit tests covering all variants + the spring-forward / fall-back / clamping edge cases per spec._
- [x] **`PaletteCatalog`** — single source for the available `ColorToken` and `FluxItIconRef` values surfaced in the Create List picker. Avoids "what icons can the user pick?" being answered in three places. _Slice 7 (2026-05-28); shipped as `object PaletteCatalog` wrapping `ColorToken.entries` (6 swatches) and `FluxItIconRef.entries` (8 icons). v1 surfaces the full enum; v2 picker-subset / A/B refinements wrap the catalog without touching the enum. Tests assert entry-list parity + the exact 6 / 8 counts (catches accidental enum drift)._

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
- [x] **Fakes** (in `commonTest`):
  - `FakeListsRepository`, `FakeItemsRepository`, `FakeRemindersRepository`, `FakePhotosRepository` — backed by in-memory `MutableStateFlow`s.
  - `FakeClock(initial)` with `advanceBy`.
  - `FakeIdGenerator(prefix = "id")` — yields `id-1, id-2, …`.
  - `FakeReminderScheduler` with controllable failure modes.
  - `RecordingAnalyticsSink` to assert event emission.
  _Partial — landing in waves. **Slice 8 (2026-05-28):** `FakeClock(initial, advanceBy, setTo)` reusable fixture._ **Slice 9 (2026-05-28):** `FakeListsRepository` + `FakeItemsRepository` — MutableStateFlow-backed, tombstone-filtering on reads, sort-order minting + per-list compaction via `SortOrderArithmetic`, `NotFound` on writes to missing/tombstoned ids. Counters NOT computed inside `FakeListsRepository` (use-case tests combine with `FakeItemsRepository` via `flow.combine` when they need the dashboard projection). Cascade NOT implemented in fakes — per ADR-006b that's a use-case-layer concern. 15 new tests cover read/write/observe contract + key behaviors (search, reorder + brackets, newest-at-top, completed-section partitioning, clearCompleted count + filtering, NotFound paths). `FakeRemindersRepository` + `FakePhotosRepository` deferred to **Slice 10** alongside the use cases that consume them. `FakeIdGenerator` is currently an inline `IdGenerator { counter }` lambda in each test file — extracting to a shared `FakeIdGenerator(prefix)` fixture lands when a §7 slice first needs the named class. `FakeReminderScheduler` + `RecordingAnalyticsSink` land with the slices that introduce the underlying ports._
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

- **2026-05-28** — Slice 9: §11 repository fakes — Lists + Items wave.
  New files in `:shared:domain` commonTest:
  `repository/FakeListsRepository.kt` (MutableStateFlow-backed,
  internal `Row` carrying full storage shape including `sortOrder`/
  `createdAt`/`updatedAt`/`deletedAt`; reads filter tombstones +
  sort by sort_order; `create` mints id via injected `IdGenerator`
  and `now` via injected `Clock`, lands new lists at the top per
  the §12 row 5 newest-at-top resolution; `reorder` uses
  `SortOrderArithmetic.between` for bracket midpoint + triggers a
  per-list `compact()` when `needsCompaction` flips true; `delete`
  is soft via `deletedAt`; counters left at 0 since list-item
  rollups are a use-case `flow.combine` concern, not a repo
  responsibility);
  `repository/FakeItemsRepository.kt` (same pattern + `add` appends
  to active-section tail to match the `SqlItemsRepository` inline-
  composer behavior; `observeByList` partitions live items into
  active/completed sections + computes the ItemsSection rollups;
  `clearCompleted` returns the count cleared; `update` is full-
  replacement matching the ItemPatch shape Slice 4 ratified).
  Build script: `:shared:domain` commonTest dep added —
  `libs.kotlinx.coroutines.test` — for `runTest`. Tests:
  `FakeListsRepositoryTest` (7 — empty → create flow; observe + null
  after delete; search lowercase substring; rename + setStarred +
  updateAppearance all reflected; NotFound on missing or
  tombstoned id; reorder bracket math; newest-at-top after two
  creates) and `FakeItemsRepositoryTest` (8 — add → active section;
  setCompleted moves between sections; update applies patch
  atomically; clearCompleted with count + filtering; clearCompleted
  on empty returns 0; observeByList filters to owning list;
  NotFound on missing id; observe(id) emits null after delete).
  Style detour: ktlint's `function-signature` +
  `multiline-expression-wrapping` + `chain-method-continuation`
  rules forced several reformats — applied via
  `:shared:domain:ktlintFormat`. While in the file also dropped a
  dead-code `.map { r -> if (r.deletedAt != null) r else r }`
  no-op left over from an in-flight refactor of `compact()`.
  `FakeRemindersRepository` + `FakePhotosRepository` deferred to
  **Slice 10** alongside the use cases that need them
  (`ScheduleReminder`, `AttachPhotoToItem`, `PhotoJanitor`).
  _Commit `3cfc74b`._
- **2026-05-28** — Slice 8: §8 `RecurrenceCalculator` + reusable
  `FakeClock` fixture. New files: `rule/RecurrenceCalculator.kt`
  (`object` with `nextFireAfter(rule, after, tz): Instant?` over a
  shared `findNext(after, tz, advance)` core loop that wall-clock-
  round-trips each candidate to detect DST spring-forward gaps and
  advances another iteration; 400-iteration safety cap; fall-back
  ambiguity resolved by kotlinx-datetime's default earlier-of-
  ambiguous policy); `port/FakeClock.kt` (commonTest fixture —
  initial Instant, `advanceBy(Duration)` with non-negative guard,
  `setTo(Instant)` for fixture reset). Tests: `FakeClockTest` (5 —
  initial-then-stable; advance-by accumulates; zero allowed;
  negative throws; setTo jumps) + `RecurrenceCalculatorTest` (22 —
  None/Daily/Weekly/Monthly happy paths in UTC; Daily DST spring-
  forward skip + fall-back first-occurrence in Europe/London;
  Daily across Kolkata's fixed +5:30; Weekly single/multi-day +
  non-included-day advance; Weekly empty-set throws; Monthly
  one-month advance + Jan 31 → Feb 28 (common) → Feb 29 (leap) →
  Mar 31 (restoration) clamping chain + 30-day-month clamp +
  Dec→Jan rollover; Monthly invalid-day throws; three iteration
  property tests covering Daily/Monthly/Weekly across London/Apia/
  Kolkata for monotonicity + per-variant invariants). Two style
  detours: ktlint's `chain-method-continuation` rule mangled the
  `kotlin.runCatching {…}.also {…}` idiom across multiple test
  bodies — replaced with `assertFailsWith<IllegalArgumentException>`
  blocks (the idiom other domain tests already use). Konsist Phase
  04 §1 rule passed unchanged — no new forbidden imports introduced.
  _Commit `a12ac91`._
- **2026-05-28** — Slice 7: §8 pure business rules first wave. New
  files under `:shared:domain/rule/`:
  `rule/CompletionCalculator.kt` — `object` with `fraction`,
  `display`, `isFullyComplete` (all guarded with
  `require(total >= 0)` + `require(completed in 0..total)`; empty
  list returns `0f` / `false` per the dashboard "0/0 is not
  complete" intent);
  `rule/SortOrderArithmetic.kt` — `object` with `between(prev, next)`
  + `needsCompaction(orders)` + `SEED_SORT_ORDER = 1.0` +
  `REBALANCE_EPSILON = 1e-9` (constants match
  `SqlListsRepository`'s companion verbatim; the data layer
  duplicates the math inline today and will migrate to consume this
  helper when a §7 use case first needs to compute targets in pure
  code before calling the repo);
  `rule/PaletteCatalog.kt` — `object` wrapping `ColorToken.entries`
  (6 swatches) + `FluxItIconRef.entries` (8 icons) as the single
  Create-List-picker source.
  Tests in commonTest: `CompletionCalculatorTest` (9 — happy paths,
  guard rejections, the 0/0-is-not-complete edge), `PaletteCatalogTest`
  (4 — entry-list parity + exact counts catching accidental enum drift),
  `SortOrderArithmeticTest` (11 — between/needsCompaction unit cases,
  threshold-exclusive boundary, the §8 random-inserts property test +
  a targeted "tightest-gap inserts trigger compaction" test).
  First-attempt stress test asserted `compactions >= 1` on the random
  insert loop and failed (random selection across 3 brackets doesn't
  concentrate depth enough in 1000 iterations to hit 1e-9); split
  into the property + targeted tests above. `RecurrenceCalculator`
  is deferred to its own slice with the time-zone / DST property
  surface and the `FakeClock` advance-by fixture. _Commit `826753f`._
- **2026-05-28** — Slice 6: §6 error model build-out. New file
  `:shared:domain/error/DomainError.kt`: sealed class with four
  variants (`Validation(field, rule: ValidationError)`, `NotFound
  (entity, id)`, `Conflict(message)`, `StorageFailure(cause)`) plus
  the `DataError.toDomain(entity: String = "unknown"): DomainError`
  extension that bridges Phase 03's data-layer failure sum to the
  use-case-layer failure sum at the repository → use-case seam.
  Design call-out (encoded in KDoc + test): `DataError.Validation`
  (storage-side FK/unique constraint violation) maps to
  `DomainError.Conflict`, NOT `DomainError.Validation` — the latter
  is reserved for use-case-edge input validators (e.g.
  `TrimmedNonBlank.of` failures); user-input validation never
  travels through DataError because it runs before the repo call.
  `DomainError.SchedulerFailure(reason: SchedulerError)` and
  `DomainError.CaptureFailure(reason: CaptureError)` deferred to
  the slices that introduce `ReminderScheduler` / `PhotoCapture`
  ports — adding them now would require placeholder error sums that
  the consuming ports would then have to reshape. `error/Outcome.kt`
  gained `mapError` and `fold` extensions — `mapError` is the
  combinator the new mapper pattern uses
  (`repo.create(...).mapError { it.toDomain(entity = "List") }`),
  `fold` is the standard "pattern-match inline and produce one
  output" companion. Tests in commonTest: `DomainErrorMapperTest`
  (6: entity-label propagation + default; conflict reason
  preservation; storage/unknown cause preservation; the validation
  → conflict design call-out) and `OutcomeCombinatorsTest` (5:
  mapError on Ok/Err; the headline use-case lift pattern;
  fold on Ok/Err). The §6 `Outcome` re-export from `:core:core-utils`
  is still deferred — lands with the first non-domain consumer
  (Phase 05 state layer). _Commit `5fa6107`._
- **2026-05-28** — Slice 5: §5 platform ports first wave. New files in
  `:shared:domain` commonMain: `port/Clock.kt` (`fun interface Clock`
  with `companion object { val System }` binding to
  `kotlinx.datetime.Clock.System`) and `port/IdGenerator.kt`
  (`typealias IdGenerator = dev.franzueto.fluxit.core.utils.IdGenerator`
  — re-export so the §5 surface stays coherent at the domain port
  package without duplicating the type or breaking ADR-006a's
  core-utils ownership). Build script gained
  `implementation(project(":core:core-utils"))`. `ListEntities.kt`
  gained `ListId.Companion.new(idGen)` — the §2 deferred row from
  Slice 3 — using the new port. Tests in commonTest:
  `port/ClockTest` (System bracketed by direct Clock.System calls;
  fun-interface lambda construction returns the lambda's value
  stably) and `model/ListIdFactoryTest` (factory delegates to
  injected IdGenerator; sequential calls produce distinct ids via a
  counter-based fake). Spotless required a one-off line-wrap on the
  Clock smoke test; applied via `:shared:domain:spotlessApply`.
  Heavier ports (`ReminderScheduler`, `PhotoCapture`, `AppLogger`,
  `AnalyticsSink`, `ConfigProvider`) wait for the slices that
  consume them. _Commit `2d9e27c`._
- **2026-05-28** — Slice 4: §2/§3 shape reconciliation (decision-only,
  no code). Two questions outstanding from the original slicing plan
  closed: **(1)** `ReminderOwner` shape — discovered during this
  slice that Phase 03 §5 already shipped the spec-shape sealed
  interface (`OfList(ListId)` / `OfItem(ItemId)`), with
  `ReminderOwnerType` serving as a storage-side discriminator the
  data mapper converts to/from at the SQL boundary. No churn needed;
  §3 row ticked with the rename note (`List` → `OfList` to avoid
  `kotlin.collections.List` collision). **(2)** `ItemPatch` shape —
  decided **Option A** (user-confirmed): keep `ItemPatch` as a
  full-replacement payload at the data edge (matches the atomic
  `Items.sq` UPDATE shipped Phase 03 §5); push the `Optional<T>`
  "don't touch vs. set to null" concept to the use-case API
  (`UpdateItemDetails` in §7), which reads-current then emits a
  complete `ItemPatch`. `Optional<T>` therefore lives in §6 as a
  use-case parameter-shape primitive introduced when §7 needs it
  first, **not** as a domain type embedded in `ItemPatch`. Trade-off:
  one extra single-row SELECT per item update; the win is zero data-
  layer churn + spec semantics preserved at the use-case boundary.
  All §3 rows ticked with carry-forward / deferral annotations;
  `TrimmedNonBlank` re-typing of `ListDraft.name` / `ItemDraft.title`
  deferred to the §7 use-case slices that own the validation. _Commit
  `a151f2e`._
- **2026-05-28** — Slice 3: §2 value-object gap fill. New files in
  `:shared:domain` commonMain: `model/RelativePath.kt` (`value class`
  with non-blank `init` guard), `model/TrimmedNonBlank.kt` (`value
  class` with private ctor + `Companion.of(raw, maxLen?)` factory
  returning `Outcome<TrimmedNonBlank, ValidationError>`), and
  `error/ValidationError.kt` (seed of the §6 sum: `Empty`,
  `TooLong(max)`, `InvalidFormat` — only the variants Slice 3 needs;
  rest grow with the use-case slices that introduce them). Tests in
  commonTest: 7 for `TrimmedNonBlank` (empty/whitespace/happy/inner-
  whitespace-preserved/at-max/over-max/null-disables-cap) and 3 for
  `RelativePath` (typical/empty/whitespace). All Phase 03 §2 value-
  class rows ticked; pulled-forward IDs annotated as Phase 03 carry-
  forward. The `ListId.Companion.new(idGen)` factory deferred to the
  §5 slice that introduces the `IdGenerator` port. §12 length-cap
  open questions (description, list-name) stay open — the
  `TrimmedNonBlank.of(maxLen)` parameter is the seam they'll flow
  through when answered. _Commit `597c035`._
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
