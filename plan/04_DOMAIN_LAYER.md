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
- [x] **`ReminderScheduler`**
  - `suspend fun schedule(reminder: Reminder): Result<PlatformHandle, SchedulerError>`
  - `suspend fun cancel(handle: PlatformHandle): Result<Unit, SchedulerError>`
  - `suspend fun rescheduleAll(active: List<Reminder>): Result<Unit, SchedulerError>` — used on app start / boot-completed (Android) to repopulate the OS-level schedule.
  - `value class PlatformHandle(val raw: String)`.
  - `sealed class SchedulerError { object PermissionDenied; object SystemBusy; data class Unknown(val cause: Throwable?) }`.
  _Slice 13C (2026-05-28); shipped in `port/ReminderScheduler.kt` with `PlatformHandle`, `SchedulerError` (the three variants verbatim), and the three suspend methods. Returns **`Outcome`** (not `kotlin.Result`) per ADR-007 — the punch list's `Result<…>` was generic notation. Consumed by `ScheduleReminder` / `CancelReminder` / `RehydrateReminders` / `DeleteList`._
- [x] **`PhotoStorage`** — defined in Phase 03 §7; redeclared here authoritatively. _Already present in `port/PhotoStorage.kt` (carried from Phase 03); ticked on Slice 13D when its first domain consumer (`PhotoJanitor`) landed._
- [x] **`PhotoCapture`**
  - `suspend fun capture(): Result<CapturedPhoto, CaptureError>` — opens camera UI.
  - `suspend fun pickFromLibrary(): Result<CapturedPhoto, CaptureError>` — opens system picker.
  - `data class CapturedPhoto(bytes: ByteArray, mime: String, widthPx: Int, heightPx: Int)`.
  - `sealed class CaptureError { object PermissionDenied; object UserCancelled; data class Unknown(cause: Throwable?) }`.
  _Slice 13D (2026-05-28); shipped in `port/PhotoCapture.kt` verbatim, returning **`Outcome`** per ADR-007. `CapturedPhoto` has hand-written structural `equals`/`hashCode` (ByteArray needs `contentEquals`). Consumed by `AttachPhotoToItem`._
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
  _Slice 6 (2026-05-28): four variants shipped (`Validation`, `NotFound`, `Conflict`, `StorageFailure`). `SchedulerFailure` / `CaptureFailure` deferred to the slices that introduce the `ReminderScheduler` / `PhotoCapture` ports respectively — adding them now would require placeholder `SchedulerError` / `CaptureError` types that the consuming ports would then have to reshape. KDoc on the sealed class names the two deferred variants so future readers see the gap is intentional._ **Slice 13C (2026-05-28):** `SchedulerFailure(reason: SchedulerError)` shipped alongside the `ReminderScheduler` port (DomainError now imports `port.SchedulerError`). `CaptureFailure` still deferred to the Photos slice (13D)._ **Slice 13D (2026-05-28):** `CaptureFailure(reason: CaptureError)` shipped alongside the `PhotoCapture` port — all six `DomainError` variants now present._
- [x] **`sealed class ValidationError`** — `Empty`, `TooLong(max: Int)`, `OutOfRange(min, max)`, `InvalidFormat`. Used by `TrimmedNonBlank.of` and the `ReminderSpec` validator. _Slice 3 (2026-05-28) seeded `Empty`, `TooLong(max)`, `InvalidFormat`. **Slice 13C (2026-05-28):** `ScheduleReminder`'s `firesAt > Clock.now()` guard needed the `ReminderSpec` validator's variant — but a single-sided "must be after now" bound on an `Instant` doesn't fit the speculative two-sided `OutOfRange(min, max)`, so a precise `NotInFuture` data object shipped instead. `OutOfRange(min, max)` stays unbuilt until a real numeric-range validator needs it._
- [x] **`Result<T, E>`** — adopt `kotlin.Result<T>`? **No** — its error is fixed to `Throwable`, which loses type information. Use a tiny in-house `sealed interface Outcome<out T, out E> { Success(value: T); Failure(error: E) }` with `map`, `flatMap`, `mapError`, `fold`. (Name: `Outcome` to avoid collision with `kotlin.Result`.) Lives in `:shared:domain` and is re-exported from `:core:core-utils` for non-domain callers. _Phase 03 §5 shipped `Outcome<out T, out E>` with `Ok`/`Err` constructors (reconciled spelling per ADR-007) and `map` / `flatMap`. Slice 6 (2026-05-28) added `mapError` and `fold` so the `repo.create(draft).mapError { it.toDomain(entity = "List") }` use-case pattern works. Re-export from `:core:core-utils` deferred to the slice that introduces the first non-domain consumer (likely Phase 05's state layer)._
- [x] Every `DataError` from Phase 03 maps to a `DomainError` via a single extension function `DataError.toDomain(): DomainError`. Tested. _Slice 6 (2026-05-28); shipped as `DataError.toDomain(entity: String = "unknown"): DomainError` so call sites supply the entity-type hint for `NotFound` (e.g. `it.toDomain(entity = "List")`). All five `DataError` variants covered with 6 unit tests including the design call-out that `DataError.Validation` (storage-side constraint violation) maps to `DomainError.Conflict`, **not** `DomainError.Validation` — the latter is reserved for use-case-edge input validators._
- [x] **`Optional<T>`** — use-case parameter-shape primitive: `sealed interface Optional<out T> { data object Unset; data class Set<out T>(val value: T) }` + an `orElse(current: T): T` fold. Distinguishes "don't touch" from "set (possibly to null)" without `null`-vs-absent ambiguity. _Slice 13B (2026-05-28); introduced by `UpdateItemDetails` (the partial-update use case that first needs it). Lives in `error/` per the §4 reconciliation (Slice 4) — **not** on the data-edge `ItemPatch`, which stays a full-replacement payload for the atomic SQL UPDATE. `Optional<T>` for non-nullable target fields, `Optional<T?>` for nullable ones (`Set(null)` clears). `orElse` is the one-liner that folds an intent over the current value into a complete payload._

## 7. Use cases

Each use case is a small class with `operator fun invoke(...)` (or `suspend operator fun invoke`), constructor-injected dependencies, no shared mutable state. **Group by feature**, not by entity, so feature phases can grep by directory.

### Lists

- [x] **`ObserveLists`** — `operator fun invoke(): Flow<List<ListSummary>>` — debounce/sort applied here, not in repo. _Slice 11A (2026-05-28); trivial delegate to `ListsRepository.observeAll()`. Returns `Flow` (not `Outcome`) — the typed-error channel is for command use cases; a reactive read has no single fold-able failure. Debounce/sort deferred to the state layer (Phase 05) per §9 (`stateIn`/`shareIn` are state-layer choices)._
- [x] **`SearchLists`** — `operator fun invoke(query: String): Flow<List<ListSummary>>`. Empty query → all. Trims, lower-cases, applies `query.length >= 1` rule. _Slice 11A (2026-05-28); validator-discipline pattern — a blank query trims to `""`, which the repo contract treats as "match everything", so there's no `query.length >= 1` rejection (an empty query is a legitimate result, not an error). Normalises (trim + lowercase) then delegates to `repo.search`._
- [x] **`CreateList`** — validates draft → `repo.create` → if `draft.reminder != null` then `ScheduleReminder` → emits `AnalyticsEvent.ListCreated`. _Slice 11A (2026-05-28); validates `draft.name` via `TrimmedNonBlank.of` (blank → `DomainError.Validation(field="name", rule=Empty)`, directly — never via `DataError`), persists the **trimmed** name, lifts repo errors via `mapError { it.toDomain(entity="List") }`. **Spec/reality reconciliation:** the punch list said "mint id via `IdGenerator`", but the shipped `ListsRepository.create` contract mints the id itself (atomic with sort_order + timestamps) and returns it — so `CreateList` delegates id-minting to the repo and carries no `IdGenerator` dep. **Deferred:** `ScheduleReminder` chaining waits for `ListDraft.reminder` (§3 carry-forward, not yet added) + the `ReminderScheduler` port slice; `AnalyticsEvent.ListCreated` waits for §5's `AnalyticsSink` port (noted in KDoc)._
- [x] **`UpdateListAppearance`** — change icon/color, validates color is in palette. _Slice 11B (2026-05-28); validates both `icon` and `color` against `PaletteCatalog` before persisting, then lifts repo failures via `toDomain(entity="List")`. **Forward-looking guard, not a reachable v1 failure:** `FluxItIconRef`/`ColorToken` are enums and the v1 catalog wraps the full `.entries`, so every well-typed argument is already in the catalog — the membership check can't fail today. Written anyway so the moment the catalog narrows to a subset (v2 per-tier picker / A/B, per PaletteCatalog's KDoc) it starts rejecting out-of-catalog values as `ValidationError.InvalidFormat` with no code change here. Tested via an every-catalog-value pass-through (the guard never rejects a v1-reachable input) rather than an unconstructable out-of-catalog rejection._
- [x] **`RenameList`** _Slice 11A (2026-05-28); validates new name via `TrimmedNonBlank.of` (blank → `DomainError.Validation`), persists trimmed value, lifts repo `DataError` via `mapError { it.toDomain(entity="List") }` — a write against a missing/tombstoned id surfaces as `DomainError.NotFound(entity="List", …)`._
- [x] **`SetListStarred`** _Slice 11B (2026-05-28); trivial delegate to `repo.setStarred` + the standard `toDomain(entity="List")` lift (missing/tombstoned id → `DomainError.NotFound`). No input to validate (Boolean + typed id)._
- [x] **`ReorderList`** — input is `(movedId, beforeId?, afterId?)`; computes new fractional sort_order in pure code, calls `repo.reorder`. _Slice 13A (2026-05-28); `(movedId, previous?, next?)` bracket → delegate to `repo.reorder` + `toDomain(entity="List")` lift. **Spec/reality reconciliation:** the punch list said "computes new fractional sort_order in pure code", but the shipped `ListsRepository.reorder` contract owns the `SortOrderArithmetic` math + rebalance (atomic with the write), so the use case is a pure delegate — same division of labour as the already-shipped `ReorderItem`. Either neighbour may be `null` for the dashboard endpoints; a missing/tombstoned id surfaces as `DomainError.NotFound(entity="List")`._
- [x] **`DeleteList`** — soft-deletes list AND cancels all active reminders for it. Returns `Outcome<DeletedListSummary, DomainError>` so UI can offer undo (per Phase 03 open question — supports either UX). _Slice 13C (2026-05-28); reads the list header via `observe(id).first()` (missing → `DomainError.NotFound(entity="List")`), cancels every active reminder owned by the list by **composing `CancelReminder`** (OS disarm first, then DB tombstone), then soft-deletes the row, returning `DeletedListSummary(id, name, cancelledReminderIds)`. A `SchedulerFailure` from any reminder cancel aborts **before** the list is deleted (retryable; no deleted list left with live OS notifications). **Cascade (ADR-006b):** items are NOT explicitly tombstoned — the list tombstone hides them and Lists→Items cascade is the data-layer FK concern; reminders ARE cancelled explicitly because they carry OS-level side effects a tombstone alone wouldn't disarm. New `DeletedListSummary` model (non-persistence shape) carries the cancelled reminder ids so a future `UndoDeleteList` can reschedule. Four-test suite (happy w/ armed reminders, no-reminders, NotFound, scheduler-failure-aborts)._
- [ ] **`UndoDeleteList`** — restores `deleted_at = NULL`; reschedules reminders; only valid within an undo window the *state* layer enforces (domain doesn't know about wall-clock undo windows beyond accepting the call). _Slice 13C (2026-05-28): **blocked** — needs a data-layer restore primitive (`deleted_at = NULL` write) that the shipped `ListsRepository` doesn't expose, same gap as `UndoDeleteItem`/`RestoreItems`. `DeleteList` already captures the cancelled reminder ids in `DeletedListSummary` so this lands as a pure delegate once the data layer surfaces restore._

### Items

- [x] **`ObserveListDetail`** — combines `ListsRepository.observe(id)` + `ItemsRepository.observeByList(id)` into one stream of `(detail, sections)`. Uses `kotlinx.coroutines.flow.combine`. _Slice 12 (2026-05-28); `combine` of the two flows into a `ListDetailView(detail: ListDetail?, items: ItemsSection)` data class (defined in the use-case file). `detail` is nullable — a soft-deleted/never-existed list emits `null` alongside an empty `ItemsSection`; the state layer decides how to render "list is gone". Reactive read → returns `Flow`, not `Outcome`._
- [x] **`AddItem`** — validates title; appends to active section. _Slice 12 (2026-05-28); validates `draft.title` via `TrimmedNonBlank.of` (blank → `DomainError.Validation(field="title")`, directly), persists the trimmed title, lifts repo errors via `toDomain(entity="Item")`. Repo owns id/sort_order/timestamps + the append-to-active-tail placement._
- [x] **`ToggleItemCompleted`** — `setCompleted(id, !current)`. Single use case (no separate `Complete`/`Uncomplete`). _Slice 12 (2026-05-28); reads current via `items.observe(id).first()` (missing/tombstoned → `DomainError.NotFound(entity="Item")` directly), writes the negation via `setCompleted` + `toDomain` lift. Read-then-write isn't atomic; last-writer-wins through the observed flow is acceptable for the single-user local store (§9 keeps domain dispatcher-agnostic)._
- [x] **`UpdateItemDetails`** — backs the Edit Item screen (title, description, photo). _Slice 13B (2026-05-28); the use case that **introduces `Optional<T>`** (§6). Each editable field is an `Optional` (`title: Optional<String>`, `subtitle`/`description`: `Optional<String?>`, `photoId: Optional<PhotoId?>`, all defaulting to `Unset`). Reads the current item via `observe(id).first()` (missing/tombstoned → `DomainError.NotFound(entity="Item")` directly), validates `title` **only when supplied** via `TrimmedNonBlank.of` (blank → `DomainError.Validation`; title is non-nullable so `Unset` keeps it and it can't be cleared), folds each `Optional` over the current value via `orElse` into a complete full-replacement `ItemPatch`, then lifts the repo write via `toDomain(entity="Item")`. Six-test suite: Unset leaves fields untouched, `Set(null)` clears a nullable field, title trimming, blank-title rejection (asserts the write never reached the repo), NotFound, and an `Outcome.fold` use-site._
- [x] **`AttachPhotoToItem`** — orchestrates `PhotoCapture` (or `pickFromLibrary`) → optional re-encode hook → `PhotosRepository.ingest` → `ItemsRepository.update(itemId, photoPatch)`. Returns `Outcome<PhotoId, DomainError>`. _Slice 13D (2026-05-28); `invoke(itemId, source: PhotoSource{CAMERA,LIBRARY})`. `PhotoCapture` opens camera/picker (`CaptureError` → `DomainError.CaptureFailure`, incl. `UserCancelled` = quiet abort) → `ingest` (file-first-then-row, lift `toDomain(entity="Photo")`) → points the item at the new photo by **composing `UpdateItemDetails`** with `photoId = Optional.Set(id)` (so a missing item is `NotFound`). Re-encode hook lives in the platform impl above the port. **Edge:** if the item vanishes between ingest and update the photo is left unreferenced; `PhotoJanitor` reclaims it (a pre-check race can't be closed without a cross-repo transaction). Four-test suite._
- [x] **`DetachPhotoFromItem`** — clears `photo_id`, schedules `PhotoJanitor` to GC the file later. _Slice 13D (2026-05-28); reads the item (missing → `NotFound`; no photo → no-op `Ok`), clears `photo_id` via `UpdateItemDetails(photoId = Optional.Set(null))`, then runs `PhotoJanitor` on the detached photo **inline** (single-user local store, cheap) rather than queuing — the janitor no-ops when the photo is still referenced elsewhere. Four-test suite incl. the shared-photo-survives branch._
- [x] **`ReorderItem`** _Slice 12 (2026-05-28); `(movedId, previous?, next?)` bracket → delegate to `repo.reorder` (which owns the `SortOrderArithmetic` math + rebalance) + `toDomain(entity="Item")` lift._
- [x] **`SetItemStarred`** _Slice 12 (2026-05-28); trivial delegate + `toDomain(entity="Item")` lift. Mirrors `SetListStarred`._
- [x] **`DeleteItem`** + **`UndoDeleteItem`** _Slice 12 (2026-05-28): **`DeleteItem`** ships as a delegate to `repo.delete` (soft-delete) + `toDomain(entity="Item")` lift. **`UndoDeleteItem` deferred** — the shipped `ItemsRepository` contract has no restore primitive (`deleted_at = NULL` write), so undo can't be built in domain yet; it lands once Phase 03's data layer surfaces a restore method (tracked with `RestoreItems`)._
- [x] **`ClearCompletedItems`** — bulk soft-delete. Returns `Outcome<List<ItemId>, DomainError>` (the deleted ids) so the state layer can back a single bulk-undo snackbar (per Phase 08 resolution). Implementation calls `ItemsRepository.clearCompleted(listId)` which surfaces `RETURNING id` rows from Phase 03. _Slice 12 (2026-05-28); **spec/reality reconciliation:** the shipped `clearCompleted` contract returns an `Int` **count**, not `List<ItemId>`, and there's no restore primitive — so `ClearCompletedItems` returns `Outcome<Int, DomainError>` for now (delegate + `toDomain(entity="Item")` lift). The id-returning variant + `RestoreItems` land together once Phase 03 surfaces the `RETURNING id` rows + a bulk-restore method._
- [ ] **`RestoreItems`** — `suspend operator fun invoke(ids: List<ItemId>): Outcome<Unit, DomainError>`. Bulk reverse of `ClearCompletedItems`: sets `deleted_at = NULL` for each id in a single transaction; idempotent (already-active items are no-ops). Also used by the bulk-undo path of any future v2 "select multiple → delete" UX. _Deferred (with `UndoDeleteItem`): blocked on a data-layer restore method that the shipped `ItemsRepository` contract doesn't expose._

### Reminders

- [x] **`ScheduleReminder`** — validates `firesAt > Clock.now()`; persists row → calls `ReminderScheduler.schedule` → writes back the `PlatformHandle` via `RemindersRepository.rebindPlatformHandle`. If platform schedule fails with `PermissionDenied`, row stays with `is_active = 0` and `platform_handle = NULL`; surfaces `SchedulerFailure(PermissionDenied)` so UI can prompt for permission and retry. _Slice 13C (2026-05-28); past/now `firesAt` → `DomainError.Validation(field="firesAt", rule=NotInFuture)` directly (no side effects). Persists via `repo.schedule`, constructs the `Reminder` for the platform call, arms via `scheduler.schedule`, rebinds the handle on success. **Spec/reality reconciliation:** the contract has no "deactivate but keep" primitive distinct from `cancel` (which tombstones + flips active), so on platform failure the just-created row is **tombstoned** (best-effort cleanup) rather than left at `is_active=0`; "retry" = re-invoking the use case (fresh row), which matches the UI's permission-prompt-then-retry flow. Four-test suite (happy/rebind, past-firesAt rejection, platform-failure tombstone, fold)._
- [x] **`CancelReminder`** — cancels at platform first, then DB. Idempotent. _Slice 13C (2026-05-28); takes `(owner, id)` rather than a bare id because the shipped `RemindersRepository` exposes no `observe(id)` — only `observeForOwner`. Looks the reminder up in `observeForOwner(owner).first()`; **idempotent** (unknown/already-cancelled → `Ok`). Disarms the platform via `scheduler.cancel(PlatformHandle)` only when a handle is bound; a `SchedulerError` aborts before the DB write (retryable). Four-test suite._
- [x] **`RehydrateReminders`** — runs on app start: `RemindersRepository.selectActive` → `ReminderScheduler.rescheduleAll`. Handles boot-completed (Android) and cold-start (iOS) consistently. _Slice 13C (2026-05-28); **spec/reality reconciliation:** the contract has no `selectActive` — only `observeUpcoming(limit)` (active rows firing after the subscription "now"), which is the right set to re-arm (past-due rows are moot for rehydration). Reads `observeUpcoming(Int.MAX_VALUE).first()` for an unbounded one-shot snapshot → `scheduler.rescheduleAll`. `SchedulerError` → `SchedulerFailure`. Three-test suite (batch re-arm, empty store, scheduler failure)._
- [x] **`ObserveRemindersForList`** / **`ObserveRemindersForItem`**. _Slice 13C (2026-05-28); reactive reads → `Flow<List<Reminder>>`, trivial delegates wrapping the id in `ReminderOwner.OfList`/`OfItem` for `observeForOwner`. One owner-isolation test each (a list reminder doesn't leak into the item feed, and vice-versa)._

### Photos / housekeeping

- [x] **`PhotoJanitor`** — `selectOrphaned(olderThan = 24h)` → for each: `PhotoStorage.delete` → `PhotosRepository.deleteIfOrphaned`. Run on app start and after `DetachPhotoFromItem`. _Slice 13D (2026-05-28); shipped as a **per-photo** janitor `invoke(photoId): Outcome<Boolean, DomainError>` (`true` = file reclaimed). Reads the `relativePath` (already-gone → `Ok(false)`), calls `deleteIfOrphaned` (soft-deletes the row iff unreferenced), then re-reads — if the row is gone it deletes the backing file via `PhotoStorage.delete`, else leaves it (still referenced). **Spec/reality reconciliation:** the punch list's batch `selectOrphaned(olderThan=24h)` sweep is **deferred** — the shipped `PhotosRepository` exposes no enumeration primitive (only single-photo `observe`/`ingest`/`deleteIfOrphaned`), so only the per-photo form ships (the form `DetachPhotoFromItem` drives, and the form a future 24h sweep would call in a loop). Three-test suite (orphan reclaim, referenced-kept, already-gone)._

### App-level

- [x] **`InitializeApp`** — composite use case run once at startup: `RehydrateReminders` + `PhotoJanitor`. Returns `Flow<InitProgress>` so the splash/state layer can show progress (or just complete silently in v1). _Slice 13D (2026-05-28); emits `InitProgress` (`Started` → `RemindersRehydrated` → `Completed`, or terminates with `Failed(error)` if rehydration fails). **Spec/reality reconciliation:** the batch `PhotoJanitor` startup sweep is deferred (no `selectOrphaned` enumeration, see PhotoJanitor row), so the composite currently runs the reminder rehydration only; the janitor step lands here once the data layer surfaces `selectOrphaned`. `InitProgress` defined in the use-case file. Two-test suite (success sequence, failure-terminates)._

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

- [x] All `suspend` boundaries declare *what dispatcher they require*: `// Caller dispatcher: any. This use case does not block.` Annotated via KDoc, not enforced (annotation framework would be overkill). _Slice 14A (2026-05-29): every one of the 25 `usecase/` classes now carries a `**Concurrency (§9):**` KDoc paragraph — the 19 command use cases state "caller dispatcher — any; does not block; suspends only on the injected repository/port, which owns its dispatcher; domain stays dispatcher-agnostic"; the 6 reactive reads (`ObserveLists`/`SearchLists`/`ObserveListDetail`/`ObserveRemindersForList`/`ObserveRemindersForItem`/`InitializeApp`) state "cold [Flow], collected on the collector's dispatcher; no shareIn/stateIn"._
- [x] Use cases never call `withContext(Dispatchers.IO)` — that's the data layer's responsibility (SQLDelight driver decides). Domain stays dispatcher-agnostic. _Slice 14A (2026-05-29): audited — `grep -rn 'Dispatchers\.|withContext' shared/domain/src/commonMain` returns zero hits._
- [x] No `Flow.shareIn` / `stateIn` in domain — those are state-layer choices. _Slice 14A (2026-05-29): audited — `grep -rn 'shareIn|stateIn' shared/domain/src/commonMain` returns zero hits._
- [x] Cancellation: every use case is structured-concurrency-clean. Verified by a Konsist rule banning `GlobalScope` in this module. _Already enforced — `build-logic/.../ArchitectureTest.kt` test "GlobalScope and runBlocking are forbidden outside test source sets" scans the whole repo (incl. `:shared:domain`), banning both `GlobalScope` and `runBlocking` in non-test sources. No new rule needed (Slice 14A confirmed coverage)._

## 10. ADRs to write in this phase

- [x] **ADR-007** — In-house `Outcome<T, E>` type vs. `kotlin.Result<T>` vs. Arrow `Either`. Why we picked our own: typed errors, no Arrow buy-in, no kotlin.Result `Throwable` ceiling. _Drafted Proposed 2026-05-28 (Slice 1); **flipped Accepted on Slice 11A (2026-05-28)** — early flip (ahead of §13) by explicit decision: the first wave of command use cases all return `Outcome<T, DomainError>`, the Konsist `kotlin.Result` / `arrow.*` bans shipped Slice 2, and `Outcome.mapError` + `fold` are now exercised by the §7 use-case tests. Remaining §7 use cases inherit the locked decision._
- [x] **ADR-007a** — Domain owns `ColorToken` and `FluxItIconRef`; designsystem consumes them. **Supersedes ADR-006c** (which had data depending on designsystem). _Accepted 2026-05-28 (Slice 1); implementation shipped in Phase 03 §3, ADR ratifies the as-built state._
- [x] **ADR-007b** — Use-case shape: small classes with `operator fun invoke` (vs. top-level suspend functions vs. interactors with multiple methods). Why classes: DI clarity, testability, KDoc placement, ability to swap impls in tests. _Drafted Proposed 2026-05-28 (Slice 1); **flipped Accepted on Slice 11A (2026-05-28)** — early flip (ahead of §13) by explicit decision: four Lists use cases landed as classes with constructor-injected deps + a single `operator fun invoke`, their tests wire the §11 fakes through the constructor seam (the fakes counting as the second area exercising the injection contract), and the Konsist "no top-level suspend fun in usecase/" rule shipped in this slice._

## 11. Testing

- [x] **One test class per use case** — happy path, each validation branch, each `DomainError` it can produce. Uses fakes from `domain-test-fixtures` (a sibling `commonTest`-only fixture package, not a separate module yet). _Partial — landing per §7 wave. **Slice 11A (2026-05-28):** `ObserveListsTest`, `SearchListsTest`, `CreateListTest`, `RenameListTest` under `usecase/lists/`, plus a shared `ListUseCaseFixtures.kt` (seq-id `IdGenerator` lambda + fixed `FakeClock` + `draft()` builder). Each suite covers happy path, the `DomainError.Validation` branch, the `mapError { it.toDomain(entity="List") }` lift, and an `Outcome.fold` use-site. **Slice 11B (2026-05-28):** `SetListStarredTest` + `UpdateListAppearanceTest` (the latter's PaletteCatalog guard is tested via an every-catalog-value pass-through, since the rejection branch is unreachable with the v1 full-enum catalog). **Slice 12 (2026-05-28):** seven Items-area suites under `usecase/items/` — `ObserveListDetailTest`, `AddItemTest`, `ToggleItemCompletedTest`, `SetItemStarredTest`, `ReorderItemTest`, `DeleteItemTest`, `ClearCompletedItemsTest` — plus a shared `ItemUseCaseFixtures.kt` (distinct `list-`/`item-` id prefixes so a `ListId` and `ItemId` never collide in an assertion). Remaining §7 use cases get their test classes as their slices land. **Slice 13A (2026-05-28):** `ReorderListTest`. **Slice 13B (2026-05-28):** `UpdateItemDetailsTest` (6 tests incl. `Optional` Unset/`Set(null)` branches). **Slice 13C (2026-05-28):** `ScheduleReminderTest`, `CancelReminderTest`, `RehydrateRemindersTest`, `ObserveRemindersTest` (list+item owner-isolation) under `usecase/reminders/` + shared `ReminderUseCaseFixtures.kt`, and `DeleteListTest` under `usecase/lists/`. **Slice 13D (2026-05-28):** `AttachPhotoToItemTest`, `DetachPhotoFromItemTest`, `PhotoJanitorTest` under `usecase/photos/` + shared `PhotoUseCaseFixtures.kt`; `InitializeAppTest` under `usecase/app/`. Plus `FakePhotoCapture` fixture under `port/`._
- [x] **Fakes** (in `commonTest`):
  - `FakeListsRepository`, `FakeItemsRepository`, `FakeRemindersRepository`, `FakePhotosRepository` — backed by in-memory `MutableStateFlow`s.
  - `FakeClock(initial)` with `advanceBy`.
  - `FakeIdGenerator(prefix = "id")` — yields `id-1, id-2, …`.
  - [x] `FakeReminderScheduler` with controllable failure modes. _Slice 13C (2026-05-28); records `scheduled` / `cancelled` / `rescheduledBatches`, mints sequential `PlatformHandle`s, and exposes `failScheduleWith` / `failCancelWith` / `failRescheduleWith` for the `SchedulerError` branches. Under `port/`._
  - `RecordingAnalyticsSink` to assert event emission.
  _Partial — landing in waves. **Slice 8 (2026-05-28):** `FakeClock(initial, advanceBy, setTo)` reusable fixture._ **Slice 9 (2026-05-28):** `FakeListsRepository` + `FakeItemsRepository` — MutableStateFlow-backed, tombstone-filtering on reads, sort-order minting + per-list compaction via `SortOrderArithmetic`, `NotFound` on writes to missing/tombstoned ids. Counters NOT computed inside `FakeListsRepository` (use-case tests combine with `FakeItemsRepository` via `flow.combine` when they need the dashboard projection). Cascade NOT implemented in fakes — per ADR-006b that's a use-case-layer concern. 15 new tests cover read/write/observe contract + key behaviors (search, reorder + brackets, newest-at-top, completed-section partitioning, clearCompleted count + filtering, NotFound paths). `FakeRemindersRepository` + `FakePhotosRepository` deferred to **Slice 10** alongside the use cases that consume them. `FakeIdGenerator` is currently an inline `IdGenerator { counter }` lambda in each test file — extracting to a shared `FakeIdGenerator(prefix)` fixture lands when a §7 slice first needs the named class. `FakeReminderScheduler` + `RecordingAnalyticsSink` land with the slices that introduce the underlying ports. **Slice 10 (2026-05-28):** `FakeRemindersRepository` (MutableStateFlow-backed; `cancel` tombstones row + flips `isActive`; `observeUpcoming(limit)` snapshots `clock.now()` once at subscription via `flow { now = clock.now(); emitAll(state.map {…}) }` matching the §5 spec — re-subscribing yields a fresh snapshot) + `FakePhotosRepository` (file-first-then-row contract via injected `FakePhotoStorage`; `deleteIfOrphaned` honors an injected `isReferenced: (PhotoId) -> Boolean` callback — defaults to `{ false }`, use-case wiring passes a real check against `FakeItemsRepository.state` when `PhotoJanitor` lands). `FakePhotoStorage` test helper added under `port/` as the in-memory `PhotoStorage` impl. All four §11 repository fakes now in place._
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

- **2026-05-29** — Slice 14A: §9 concurrency-contract review (Phase 04
  close-out, part 1 of 3). Added a `**Concurrency (§9):**` KDoc paragraph to all
  25 `usecase/` classes — the 19 command use cases declare "caller dispatcher —
  any; does not block; suspends only on the injected repository/port, which owns
  its dispatcher; domain stays dispatcher-agnostic"; the 6 reactive reads
  (`ObserveLists`/`SearchLists`/`ObserveListDetail`/`ObserveRemindersForList`/
  `ObserveRemindersForItem`/`InitializeApp`) declare "cold `Flow`, collected on
  the collector's dispatcher; no `shareIn`/`stateIn`". Audited the source: zero
  `Dispatchers.*`/`withContext` and zero `shareIn`/`stateIn` in
  `shared/domain/src/commonMain`. Confirmed the existing Konsist rule
  ("GlobalScope and runBlocking are forbidden outside test source sets" in
  `build-logic/.../ArchitectureTest.kt`) already scans `:shared:domain` and bans
  both — no new rule needed (satisfies §9 box 4 + MASTER_PLAN rule 5). All four
  §9 boxes ticked. Docs-and-KDoc only; no behaviour change. `:shared:domain:check`
  + `:build-logic:test` green on JVM + iOS Sim. _Commit `f9cd276`._

- **2026-05-28** — Slice 13D: §7 Photos use cases + the `PhotoCapture` port +
  `InitializeApp` (closes the §7 use-case build-out). New `port/PhotoCapture.kt`
  (`CapturedPhoto` w/ hand-written ByteArray `equals`/`hashCode`, `CaptureError`
  sum {`PermissionDenied`,`UserCancelled`,`Unknown`}, `capture`/`pickFromLibrary`
  → `Outcome` per ADR-007). `DomainError.CaptureFailure(reason: CaptureError)`
  added — all six variants now present. New `usecase/photos/`: `PhotoJanitor`
  (per-photo reclaim — read path → `deleteIfOrphaned` → re-read → `storage.delete`
  if gone; returns `Boolean`), `AttachPhotoToItem` (capture → ingest → compose
  `UpdateItemDetails(photoId=Set(id))`; `PhotoSource{CAMERA,LIBRARY}`),
  `DetachPhotoFromItem` (clear via `UpdateItemDetails(photoId=Set(null))` → inline
  `PhotoJanitor`). New `usecase/app/InitializeApp` (composite → `Flow<InitProgress>`;
  `Started`→`RemindersRehydrated`→`Completed` / `Failed`). New `FakePhotoCapture`
  fixture + `PhotoUseCaseFixtures.kt`. **Spec/reality reconciliations:** (1)
  `Result<…>` → `Outcome`; (2) batch `PhotoJanitor selectOrphaned(24h)` sweep
  **deferred** — `PhotosRepository` has no enumeration primitive, only the
  per-photo form ships; (3) `InitializeApp`'s janitor step is therefore
  reminder-rehydration-only for now. 18 new tests; `:shared:domain:check` +
  `:build-logic:test` green on JVM + iOS Sim. **§7 use-case build-out is now
  complete except the data-layer-blocked items** (`UndoDeleteList`/`UndoDeleteItem`/
  `RestoreItems`/`ClearCompletedItems→List<ItemId>` need a restore/`RETURNING`
  primitive; `CreateList` analytics needs `AnalyticsSink`; batch `PhotoJanitor`
  needs `selectOrphaned`). Remaining §5 ports `AppLogger`/`AnalyticsSink`/
  `ConfigProvider` unbuilt (no §7 consumer needed them). Next: §9 concurrency
  review + §13 hand-off. _Commit `1e5734b`._

- **2026-05-28** — Slice 13C: §7 Reminders use cases + the `ReminderScheduler`
  port + `DeleteList`. New `port/ReminderScheduler.kt` (`PlatformHandle` value
  class, `SchedulerError` sum {`PermissionDenied`, `SystemBusy`, `Unknown`},
  three suspend methods returning `Outcome` per ADR-007). `DomainError` gained
  the `SchedulerFailure(reason: SchedulerError)` variant (imports `port.`);
  `ValidationError` gained `NotInFuture` (precise single-sided temporal bound;
  the speculative two-sided `OutOfRange(min,max)` didn't fit, stays unbuilt).
  New `usecase/reminders/`: `ScheduleReminder` (validate `firesAt>now` →
  persist → arm → rebind handle; platform failure tombstones the phantom row
  + surfaces `SchedulerFailure`), `CancelReminder` (`(owner,id)` lookup via
  `observeForOwner`; idempotent; OS disarm before DB), `RehydrateReminders`
  (`observeUpcoming(MAX).first()` → `rescheduleAll`), `ObserveRemindersForList`
  / `ObserveRemindersForItem` (Flow delegates). New
  `usecase/lists/DeleteList` (composes `CancelReminder` to cancel each owned
  reminder, then soft-deletes; returns `DeletedListSummary(id,name,
  cancelledReminderIds)`; scheduler failure aborts before delete). New
  `DeletedListSummary` model. New `FakeReminderScheduler` fixture (records
  calls + per-method failure injection) + `ReminderUseCaseFixtures.kt`.
  **Spec/reality reconciliations:** (1) the punch list's `Result<…>` is
  `Outcome` per ADR-007; (2) no "deactivate but keep" repo primitive distinct
  from `cancel` (tombstone), so a failed `ScheduleReminder` tombstones the row
  and retry = re-invoke; (3) no `selectActive` — `RehydrateReminders` uses
  `observeUpcoming`, the right re-arm set; (4) `CancelReminder` takes
  `(owner,id)` because there's no `observe(id)`. **Deferred:** `UndoDeleteList`
  stays blocked on a data-layer restore primitive (captures cancelled
  reminder ids now so it lands as a delegate later). 18 new tests;
  `:shared:domain:check` + `:build-logic:test` green on JVM + iOS Sim.
  _Commit `c7098e9`._

- **2026-05-28** — Slice 13B: §7 `UpdateItemDetails` + the `Optional<T>`
  primitive (§6). New `error/Optional.kt`: `sealed interface Optional<out T>
  { data object Unset; data class Set<out T>(value) }` + an `orElse(current)`
  fold. Lives in `error/` per the Slice 4 reconciliation — **not** on the
  data-edge `ItemPatch`, which stays a full-replacement payload for the
  atomic SQL UPDATE. New `usecase/items/UpdateItemDetails.kt`: each editable
  field is an `Optional` (`title: Optional<String>`, `subtitle`/`description`:
  `Optional<String?>`, `photoId: Optional<PhotoId?>`, all default `Unset`).
  Reads current via `observe(id).first()` (null → `DomainError.NotFound`
  directly), validates `title` only when supplied via `TrimmedNonBlank.of`
  (non-nullable, so `Unset` keeps it / can't clear), folds each intent over
  the current value via `orElse` into a complete `ItemPatch`, lifts the write
  via `toDomain(entity="Item")`. `UpdateItemDetailsTest` (6 tests): Unset
  leaves fields untouched, `Set(null)` clears a nullable field, title
  trimming, blank-title rejection (asserts the rejected write never reached
  the repo), NotFound, and an `Outcome.fold` use-site. `:shared:domain:check`
  + `:build-logic:test` green on JVM + iOS Sim. _Commit `0f2ce4c`._

- **2026-05-28** — Slice 13A: §7 Lists use cases wave three (kickoff) —
  `ReorderList`. New `usecase/lists/ReorderList.kt`: `(movedId, previous?,
  next?)` bracket → delegate to `ListsRepository.reorder` + the standard
  `toDomain(entity="List")` lift. **Spec/reality reconciliation:** the
  punch list said "computes new fractional sort_order in pure code", but
  the shipped `reorder` contract owns the `SortOrderArithmetic` math +
  rebalance atomically with the write, so the use case is a pure delegate
  — the mirror image of the already-shipped `ReorderItem`. `ReorderListTest`
  covers happy path (move between brackets, asserting newest-at-top
  dashboard order then the reordered order), the `toDomain` NotFound lift
  on a bogus id, and an `Outcome.fold` use-site. Opens the Slice 13 wave;
  `DeleteList` (needs the `ReminderScheduler` port → Slice 13C) and
  `UndoDeleteList` (blocked on a data-layer restore primitive
  `ListsRepository` doesn't expose) remain. `:shared:domain:check` +
  `:build-logic:test` green on JVM + iOS Sim. _Commit `6c8faf0`._

- **2026-05-28** — Slice 12: §7 Items use cases wave one. New
  `usecase/items/` package with seven use cases (ADR-007b shape):
  `ObserveListDetail` (combines `lists.observe` + `items.observeByList`
  via `flow.combine` into a `ListDetailView(detail: ListDetail?, items:
  ItemsSection)` — returns `Flow`); `AddItem` (validates `draft.title`
  via `TrimmedNonBlank.of`, persists trimmed, `toDomain(entity="Item")`
  lift); `ToggleItemCompleted` (reads current via `observe(id).first()`
  → `null` is `DomainError.NotFound(entity="Item")` directly, else writes
  `setCompleted(id, !current)`); `SetItemStarred`, `ReorderItem`,
  `DeleteItem`, `ClearCompletedItems` (delegates + the standard
  `toDomain(entity="Item")` lift). Each command returns `Outcome<_,
  DomainError>`; the two reads return `Flow`. Seven per-use-case test
  suites + shared `ItemUseCaseFixtures.kt` (distinct `list-`/`item-` id
  prefixes). **Scope + spec/reality reconciliations:** this is the
  cleanly-mapping wave; four §7 Items use cases are deferred because the
  shipped contracts don't yet support them — (1) `ClearCompletedItems`
  returns `Outcome<Int, DomainError>` (the contract's `clearCompleted`
  yields an `Int` count, not the punch list's `List<ItemId>`); (2)
  `UndoDeleteItem` + `RestoreItems` need a data-layer restore primitive
  (`deleted_at = NULL`) that `ItemsRepository` doesn't expose; (3)
  `UpdateItemDetails` needs the `Optional<T>` partial-intent primitive
  (§6) that isn't built yet — it's the use case that will introduce it;
  (4) `AttachPhotoToItem` / `DetachPhotoFromItem` need the deferred
  `PhotoCapture` port + `PhotoJanitor` (Photos slice). All annotated in
  KDoc + the §7 checklist. `:shared:domain:check` + `:build-logic:test`
  green on JVM + iOS Sim. _Commit `47e56d7`._

- **2026-05-28** — Slice 11B: §7 Lists use cases wave two — appearance +
  starred. `usecase/lists/SetListStarred.kt` (trivial delegate to
  `repo.setStarred` + the standard `toDomain(entity="List")` lift; no
  input to validate) and `usecase/lists/UpdateListAppearance.kt`
  (validates `icon` + `color` against `PaletteCatalog` before persisting,
  then lifts repo failures). The PaletteCatalog membership check is a
  **forward-looking guard, not a reachable v1 failure**: `FluxItIconRef` /
  `ColorToken` are enums and the v1 catalog wraps the full `.entries`, so
  every well-typed argument is already in-catalog — the check can't fail
  today. It's written so the moment the catalog narrows to a subset (v2
  per-tier picker / A/B, per PaletteCatalog's own KDoc) it begins
  rejecting out-of-catalog values as `ValidationError.InvalidFormat` with
  no code change here. Because the rejection branch is unconstructable
  with the full-enum catalog, `UpdateListAppearanceTest` proves the guard
  via an every-(icon, color) pass-through (the guard never rejects a
  v1-reachable input) rather than a fake out-of-catalog value.
  `SetListStarredTest` + `UpdateListAppearanceTest` each cover happy path,
  the `toDomain` NotFound lift, and an `Outcome.fold` use-site.
  `:shared:domain:check` + `:build-logic:test` green on JVM + iOS Sim.
  Closes the §7 Lists feature area (Reorder/Delete/UndoDelete remain,
  but the basic + appearance CRUD wave the punch list scoped is done).
  _Commit `d486110`._

- **2026-05-28** — Slice 11A: §7 use-case wave one (basic Lists CRUD) +
  ADR-007/007b ratification. New `usecase/lists/` package with four
  classes (ADR-007b shape — ctor-injected deps + single `operator fun
  invoke`): `ObserveLists` + `SearchLists` return `Flow` (reactive reads,
  no fold-able failure); `CreateList` + `RenameList` are `suspend` and
  return `Outcome<_, DomainError>`. Both write use cases validate at the
  edge via `TrimmedNonBlank.of` (blank → `DomainError.Validation(field=
  "name", rule=Empty)`, produced directly, never via `DataError`) and lift
  repo failures via `mapError { it.toDomain(entity="List") }`. `SearchLists`
  follows validator discipline — blank query → all results, not an error.
  **Spec/reality reconciliation:** the punch list said `CreateList` mints
  the id via `IdGenerator`, but the shipped `ListsRepository.create`
  contract mints it internally (atomic with sort_order + timestamps), so
  `CreateList` delegates id-minting to the repo and carries no
  `IdGenerator` dep. Deferred: `CreateList`'s `ScheduleReminder` chain
  (needs `ListDraft.reminder` + the scheduler port) and `AnalyticsEvent`
  emission (needs §5's `AnalyticsSink`) — both noted in KDoc.
  Tests: four per-use-case suites under `usecase/lists/` + shared
  `ListUseCaseFixtures.kt`, each covering happy path / the Validation
  branch / the `toDomain` lift / an `Outcome.fold` use-site. Added the
  Konsist "no top-level suspend fun in `:shared:domain/usecase/`" rule to
  `:build-logic`'s `ArchitectureTest` (filters `file.functions()` to
  `isTopLevel` so member `invoke` operators are exempt). **ADR-007 +
  ADR-007b flipped Proposed → Accepted** (early, ahead of §13, by explicit
  decision): the Outcome-everywhere + Konsist-ban preconditions are met for
  ADR-007, and ADR-007b's "class + invoke" shape is proven across the four
  use cases with their fakes wired through the constructor seam. `:shared:
  domain:check` + `:build-logic:test` green on JVM + iOS Sim. _Commit `15fe23c`._

- **2026-05-28** — Slice 10: §11 repository fakes wave two — Reminders +
  Photos. New files: `repository/FakeRemindersRepository.kt`
  (MutableStateFlow-backed; `observeForOwner` filters by owner + active
  + non-tombstoned; `observeUpcoming(limit)` uses `flow { val now =
  clock.now(); emitAll(state.map { … filter firesAt > now … }) }` so
  each new collector freezes its own snapshot — matches the §5 spec
  semantics + the SqlRemindersRepository's `WHERE fires_at > :nowSnapshot`
  binding; `schedule` mints id + writes row with `isActive=true,
  platformHandle=null`; `cancel` flips `isActive=false` AND sets
  `deletedAt`; `reschedule` updates fires_at + recurrence;
  `rebindPlatformHandle` round-trips the WorkManager / UN handle id);
  `repository/FakePhotosRepository.kt` (delegates `ingest` to an
  injected `PhotoStorage` for the file-first contract; `deleteIfOrphaned`
  consults an injected `isReferenced: (PhotoId) -> Boolean` callback
  defaulting to `{ false }` — soft-deletes only when unreferenced, no-op
  Ok otherwise, hard-delete + file removal stays the §7 PhotoJanitor's
  job not this method's; `observe(id)` filters tombstones);
  `port/FakePhotoStorage.kt` (in-memory `PhotoStorage` impl;
  `write(bytes, mime)` mints sequential `"photos/<n>.bin"` paths,
  `delete` returns true iff the file existed, `storedPaths` /
  `exists()` test seams). Tests: `FakeRemindersRepositoryTest` (9 —
  schedule + observe; observe filters by owner; cancel tombstones;
  reschedule updates both fields; rebindPlatformHandle round-trips +
  clears; NotFound on missing id; observeUpcoming snapshots now; limit
  + ascending sort; rejects non-positive limit) and
  `FakePhotosRepositoryTest` (6 — ingest writes file + row, observable
  with metadata; distinct ids/paths across calls; observe null for
  unknown id; deleteIfOrphaned soft-deletes unreferenced + leaves file
  for janitor; deleteIfOrphaned is no-op when referenced; NotFound for
  missing id). Style detour: ktlint reformatted both test files
  (function-signature inline, chain-method-continuation forcing the
  `.observeForOwner(listOwner).first().single().platformHandle` chain
  to split across lines); applied `:shared:domain:ktlintFormat` once.
  All four §11 repository fakes (Lists + Items from Slice 9, Reminders
  + Photos from Slice 10) now in place, ready for §7 use cases.
  _Commit `8807bf2`._
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
