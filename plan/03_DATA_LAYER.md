# Phase 03 — Data Layer

> **Goal:** Stand up the persistent data layer. SQLDelight 2 schema for lists, items, reminders, and photo refs; type-safe queries exposed as `Flow`s; repository implementations behind interfaces declared in `:shared:domain`. Local-only v1 (per ADR-003), but contracts are designed so a v2 sync engine can slot in without touching call sites.

**Owner:** Mobile platform
**Depends on:** Phase 01 (Gradle + version catalog), Phase 02 (no UI dependency, but `core-designsystem` icon names are referenced by `list_icon` token enum).
**Blocks:** Phase 04 (Domain), Phase 05 (State), all feature phases.
**Exit criteria (Definition of Done):**
- `:shared:data` builds for Android + iOS with green tests on both.
- Headless integration test: create a list → add 3 items → toggle one complete → schedule a reminder → close DB → reopen → state restored exactly.
- All repositories return `Flow` for reads and `suspend fun` returning `Result<T, DataError>` for writes.
- No raw SQL strings outside `.sq` files. No `Dispatchers.IO` / `runBlocking` in repositories.
- Konsist rule passes: `:shared:data` is the **only** module that depends on SQLDelight; the rest of the codebase sees only domain interfaces.
- Schema dump (`schema.sql`) is checked in and produced automatically by a Gradle task; CI fails if dump is out of sync.

---

## 1. Module wiring

- [ ] `:shared:data/build.gradle.kts` applies `fluxit.kmp.library` + SQLDelight plugin.
- [ ] Dependencies: `:shared:domain` (interfaces only), `kotlinx-coroutines-core`, `kotlinx-datetime`, `kotlinx-serialization-json`, SQLDelight runtime + coroutines extension + primitive adapters, Kermit.
- [ ] No dependency on Android framework, AndroidX, UIKit, Foundation. Verified by Konsist.
- [ ] iOS source set adds `app.cash.sqldelight:native-driver`; Android adds `app.cash.sqldelight:android-driver`; common defines `expect class DriverFactory`.
- [ ] Database name constant in `:shared:data`: `"fluxit.db"` (production), `":memory:"` (test).

## 2. Schema (SQLDelight `.sq` files)

All tables use **TEXT primary keys** (UUID v4 / ULID — see ADR-006a in §11) so future sync doesn't require an integer-id remap. All timestamps stored as **INTEGER (epoch milliseconds, UTC)**, converted to `kotlinx.datetime.Instant` at the adapter boundary. Soft-deletes via `deleted_at` so v2 sync can replicate tombstones; v1 queries always filter `WHERE deleted_at IS NULL`.

### `Lists.sq`

```sql
CREATE TABLE list (
    id              TEXT NOT NULL PRIMARY KEY,
    name            TEXT NOT NULL,
    icon            TEXT NOT NULL,           -- FluxItIcons enum name (e.g. "CART")
    color           TEXT NOT NULL,           -- token key (e.g. "primary.blue", "accent.sky")
    is_starred      INTEGER NOT NULL DEFAULT 0,
    sort_order      REAL NOT NULL,           -- fractional indexing for cheap reorders
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE INDEX list_sort_idx ON list (deleted_at, sort_order);
CREATE INDEX list_starred_idx ON list (is_starred) WHERE deleted_at IS NULL;
```

Queries to author (each emits `Flow` via SQLDelight coroutines extension):
- [ ] `selectAllActive` — `WHERE deleted_at IS NULL ORDER BY sort_order ASC`
- [ ] `selectById(id)`
- [ ] `searchByName(query)` — `WHERE name LIKE '%' || :query || '%' AND deleted_at IS NULL`
- [ ] `insert(list)` (full row)
- [ ] `updateMetadata(id, name, icon, color, is_starred, updated_at)`
- [ ] `updateSortOrder(id, sort_order, updated_at)`
- [ ] `softDelete(id, deleted_at)`
- [ ] `hardDelete(id)` — used only by v2 sync compaction; not called in v1
- [ ] `countActive`
- [ ] `selectWithCounts` — joins to `item` to expose `total_items`, `completed_items`, `last_activity_at` for the dashboard

### `Items.sq`

```sql
CREATE TABLE item (
    id              TEXT NOT NULL PRIMARY KEY,
    list_id         TEXT NOT NULL REFERENCES list(id) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    subtitle        TEXT,                    -- e.g. "Produce Section"
    description     TEXT,                    -- long-form notes (Edit Item screen)
    is_completed    INTEGER NOT NULL DEFAULT 0,
    is_starred      INTEGER NOT NULL DEFAULT 0,
    photo_id        TEXT REFERENCES photo(id) ON DELETE SET NULL,
    sort_order      REAL NOT NULL,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE INDEX item_list_idx ON item (list_id, is_completed, sort_order) WHERE deleted_at IS NULL;
CREATE INDEX item_photo_idx ON item (photo_id);
```

Queries:
- [ ] `selectByListGroupedByStatus` — returns two logical groups (active, completed) ordered by `sort_order` so the UI can render `TO BUY` / `COMPLETED` sections without client-side splitting.
- [ ] `selectById(id)` — for Edit Item screen.
- [ ] `insert(item)`
- [ ] `updateContent(id, title, subtitle, description, photo_id, updated_at)`
- [ ] `setCompleted(id, is_completed, updated_at)`
- [ ] `setStarred(id, is_starred, updated_at)`
- [ ] `updateSortOrder(id, sort_order, updated_at)`
- [ ] `softDelete(id, deleted_at)`
- [ ] `softDeleteCompletedByList(list_id, deleted_at)` — backs the "Clear completed" action (Phase 08). Uses SQLDelight 2 `RETURNING id` so the use case can return the deleted ids for bulk-undo.
- [ ] `countByList(list_id)` and `countCompletedByList(list_id)` — drive the `13/20` progress bar.

### `Reminders.sq`

```sql
CREATE TABLE reminder (
    id              TEXT NOT NULL PRIMARY KEY,
    owner_type      TEXT NOT NULL,           -- 'LIST' | 'ITEM'
    owner_id        TEXT NOT NULL,
    fires_at        INTEGER NOT NULL,        -- epoch ms UTC
    recurrence      TEXT,                    -- nullable; serialized RecurrenceRule (JSON) — see §3
    platform_handle TEXT,                    -- WorkManager request id / iOS notification id; nullable until scheduled
    is_active       INTEGER NOT NULL DEFAULT 1,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE INDEX reminder_owner_idx ON reminder (owner_type, owner_id) WHERE deleted_at IS NULL;
CREATE INDEX reminder_fires_at_idx ON reminder (fires_at, is_active) WHERE deleted_at IS NULL;
```

Queries:
- [ ] `selectActiveByOwner(owner_type, owner_id)`
- [ ] `selectAllUpcoming(now, limit)` — backs v2 Calendar tab.
- [ ] `insert`, `updateFiresAt`, `setPlatformHandle`, `setActive`, `softDelete`.
- [ ] `selectNeedingReschedule(now)` — recurring reminders past their last fire; used on app start to repopulate platform schedule.

### `Photos.sq`

```sql
CREATE TABLE photo (
    id              TEXT NOT NULL PRIMARY KEY,
    relative_path   TEXT NOT NULL,           -- relative to app sandbox photo root (no absolute paths)
    mime_type       TEXT NOT NULL,
    width_px        INTEGER NOT NULL,
    height_px       INTEGER NOT NULL,
    byte_size       INTEGER NOT NULL,
    created_at      INTEGER NOT NULL,
    deleted_at      INTEGER
);

CREATE INDEX photo_orphan_idx ON photo (deleted_at);
```

Queries:
- [ ] `selectById(id)`
- [ ] `insert`
- [ ] `softDelete`
- [ ] `selectOrphaned` — photos with no referencing `item` and `deleted_at` older than 24h; consumed by a janitor (§7).

### Schema versioning + migrations

- [ ] `databaseVersion = 1` declared in Gradle `sqldelight { databases { … } }`.
- [ ] `migrations/` folder pre-created (empty for v1).
- [ ] `schema.sql` dump produced by `./gradlew :shared:data:generateMainFluxItDatabaseSchema`; checked in.
- [ ] CI step runs the dump and `git diff --exit-code schema.sql` to catch unintentional schema drift.
- [ ] Migration unit-test harness wired now (even with zero migrations) so adding migration #1 in v2 is mechanical.

## 3. Column adapters

Living in `:shared:data/src/commonMain/.../db/Adapters.kt`:

- [ ] `InstantAdapter` — `kotlinx.datetime.Instant` ↔ `Long` (epoch ms).
- [ ] `BooleanIntAdapter` — `Boolean` ↔ `Int (0/1)` (SQLite has no native bool).
- [ ] `OwnerTypeAdapter` — `enum class ReminderOwnerType { LIST, ITEM }` ↔ `String`.
- [ ] `IconNameAdapter` — `enum class FluxItIconRef` ↔ `String`. Enum is the *generated* icon name set from Phase 02 — adds a build-time dependency on `:core:core-designsystem`'s generated icons file. **Trade-off acknowledged:** keeps DB rows referentially safe but couples data ↔ designsystem; documented in §11.
- [ ] `ColorTokenAdapter` — `enum class ColorToken` ↔ `String` (token keys, e.g. `PRIMARY_BLUE`, `ACCENT_SKY`).
- [ ] `RecurrenceRuleAdapter` — `RecurrenceRule` (sealed class: `None`, `Daily`, `Weekly(daysOfWeek)`, `Monthly(dayOfMonth)`) ↔ JSON via `kotlinx.serialization`. v1 scope locked by Phase 09; `Custom(rrule)` deferred to v2 (re-add the variant when needed — JSON schema is forward-compatible).

## 4. Driver factories (expect/actual)

- [ ] `commonMain`: `expect class DriverFactory { fun create(): SqlDriver }`
- [ ] `androidMain`: `actual class DriverFactory(private val context: Context)` using `AndroidSqliteDriver`. Foreign keys ON, WAL ON.
- [ ] `iosMain`: `actual class DriverFactory` using `NativeSqliteDriver`. Foreign keys ON.
- [ ] `commonTest`: in-memory driver via `JdbcSqliteDriver(IN_MEMORY)`; `Schema.create()` invoked per test.
- [ ] All drivers register the column adapters from §3 in one shared `FluxItDatabase.invoke(driver)` factory.

## 5. Repository contracts (declared in `:shared:domain`, implemented here)

Domain ships **interfaces and entity DTOs**; data ships **implementations and DB row mappers**. Each operation that can fail returns `Result<T, DataError>` (sealed `DataError` with `NotFound`, `Conflict`, `Storage`, `Validation(field)`, `Unknown(cause)`).

- [ ] **`ListsRepository`**
  - `fun observeAll(): Flow<List<ListSummary>>` (with counts; backs dashboard)
  - `fun observe(id: ListId): Flow<ListDetail?>`
  - `fun search(query: String): Flow<List<ListSummary>>` (debounce handled in state layer, not here)
  - `suspend fun create(draft: ListDraft): Result<ListId, DataError>`
  - `suspend fun rename(id, name): Result<Unit, DataError>`
  - `suspend fun updateAppearance(id, icon, color): Result<Unit, DataError>`
  - `suspend fun setStarred(id, starred): Result<Unit, DataError>`
  - `suspend fun reorder(id, between: Pair<ListId?, ListId?>): Result<Unit, DataError>` (fractional sort under the hood)
  - `suspend fun delete(id): Result<Unit, DataError>` (soft)
- [ ] **`ItemsRepository`**
  - `fun observeByList(listId): Flow<ItemsSection>` where `ItemsSection = (active: List<Item>, completed: List<Item>, total, completed)`
  - `fun observe(itemId): Flow<Item?>`
  - `suspend fun add(listId, draft: ItemDraft): Result<ItemId, DataError>`
  - `suspend fun update(itemId, patch: ItemPatch): Result<Unit, DataError>` (single op for content + photo)
  - `suspend fun setCompleted(itemId, completed): Result<Unit, DataError>`
  - `suspend fun setStarred(itemId, starred): Result<Unit, DataError>`
  - `suspend fun reorder(itemId, between): Result<Unit, DataError>`
  - `suspend fun delete(itemId): Result<Unit, DataError>` (soft)
  - `suspend fun clearCompleted(listId): Result<Int, DataError>`
- [ ] **`RemindersRepository`**
  - `fun observeForOwner(ownerType, ownerId): Flow<List<Reminder>>`
  - `fun observeUpcoming(limit): Flow<List<Reminder>>`
  - `suspend fun schedule(spec: ReminderSpec): Result<ReminderId, DataError>` — *only* writes the row; platform scheduling lives in `platform-reminders` (Phase 06) and is wired via a `ReminderScheduler` port injected here.
  - `suspend fun reschedule(id, firesAt, recurrence): Result<Unit, DataError>`
  - `suspend fun cancel(id): Result<Unit, DataError>`
  - `suspend fun rebindPlatformHandle(id, handle): Result<Unit, DataError>`
- [ ] **`PhotosRepository`**
  - `suspend fun ingest(bytes: ByteArray, mime: String, width: Int, height: Int): Result<PhotoId, DataError>` — writes file via `PhotoStorage` port (Phase 06), inserts row, returns id.
  - `fun observe(photoId): Flow<Photo?>`
  - `suspend fun deleteIfOrphaned(photoId): Result<Unit, DataError>`

## 6. Mappers

- [ ] One mapper file per table: `ListMapper`, `ItemMapper`, `ReminderMapper`, `PhotoMapper`.
- [ ] Mappers are **pure** (no IO, no `Clock`). Time injection uses `kotlinx.datetime.Clock` passed in from constructors so tests can use `FakeClock`.
- [ ] `ListSummary` derived in SQL via the `selectWithCounts` query — *not* by combining two flows in Kotlin (single source of truth, fewer recompositions).

## 7. Photo file storage contract

- [ ] `PhotoStorage` port (declared in `:shared:domain`, implemented in `platform-photo` per Phase 06):
  - `suspend fun write(bytes, mime): RelativePath`
  - `suspend fun read(relativePath): ByteArray?`
  - `suspend fun delete(relativePath): Boolean`
  - `fun resolveAbsolute(relativePath): String` (used by Compose/SwiftUI for image loading)
- [ ] **Photo janitor**: a `PhotoJanitor` use case (Phase 04) deletes orphaned photos older than 24h on app start. Acknowledged in this phase by exposing `selectOrphaned` query and `deleteIfOrphaned` in repository.
- [ ] **Crash safety**: photo file is written **first**; row insert is in a transaction that, on failure, triggers `PhotoStorage.delete(path)`. Documented in `PhotosRepository` impl + tested.

## 8. Sort order strategy (fractional indexing)

- [ ] Sort orders are `REAL` doubles. New row defaults to `(prev.sort_order + next.sort_order) / 2.0`; first row gets `1.0`.
- [ ] When the gap between two adjacent rows shrinks below `1e-9`, a `compactSortOrders(listId)` SQL function rebalances the column to evenly spaced integers. Triggered by repository on detected collision.
- [ ] Unit test: 1000 random reorders never produce gap collapse without compaction kicking in.

## 9. ID generation

- [ ] Use **UUID v4** strings (lowercase, no braces). `expect fun newId(): String` with `actual` per platform:
  - Android: `java.util.UUID.randomUUID().toString()`
  - iOS: `NSUUID().UUIDString.lowercased()`
- [ ] Tested via Konsist: no `Random` or `currentTimeMillis()` used to mint IDs anywhere.

## 10. Testing

- [ ] **Unit tests** (`commonTest`) — one per query in each `.sq` file using in-memory driver + `FakeClock`.
- [ ] **Repository tests** — happy path + error path (NotFound, Conflict on FK violation, Validation on empty name).
- [ ] **Mapper tests** — round-trip every entity.
- [ ] **Migration test harness** registered (even with zero migrations) so future migrations get coverage by convention.
- [ ] **Integration test** (the exit-criteria scenario): `IntegrationFlowTest` covers create-list → add-items → toggle → schedule-reminder → close → reopen → assert state. Runs on JVM and iosSimulatorArm64 in CI.
- [ ] **Concurrency test**: 50 concurrent `setCompleted` calls on the same item never deadlock; final state is consistent.
- [ ] Turbine for all `Flow` assertions; coroutine `runTest` with virtual time.
- [ ] Coverage target: ≥ 90% for `:shared:data` (enforced by Phase 14 gate, but goal noted now).

## 11. ADRs to write in this phase

- [ ] **ADR-006** — SQLDelight 2 over Room KMP. Why: SQL-first ergonomics, mature iOS, mature `selectXxx()` Flow extension, no kapt/ksp host gotcha on iOS.
- [ ] **ADR-006a** — UUID v4 string IDs (vs. ULID, vs. autoincrement int). Why: trivial cross-platform mint, sortable-enough via `created_at`, safe for v2 sync.
- [ ] **ADR-006b** — Soft-delete + tombstones from day one. Why: cheap insurance for v2 sync; cost is a `WHERE deleted_at IS NULL` on every read query, mitigated by partial indexes.
- [ ] **ADR-006c** — Coupling `:shared:data` to `:core:core-designsystem` for the `IconNameAdapter` enum. Why this is OK: icon names are part of the *product domain* (a list "is a cart"), not just visual chrome; designsystem is a leaf module so the dependency edge is acyclic.

## 12. Open questions for this phase

**Resolved 2026-05-19** before §2 schema code landed. Decisions below are
load-bearing for §2 (Schema), §3 (Adapters), §5 (Repository contracts),
§7 (Photo storage), and downstream Phase 06 (`platform-photo`) +
Phase 07 (dashboard delete UX) + Phase 08 (list-detail search) +
Phase 09 (reminders UX).

- [x] **Recurrence rule scope** — **Full v1 set: `None / Daily / Weekly / Monthly`.** `RecurrenceRule` sealed class ships all four variants (`None`, `Daily`, `Weekly(daysOfWeek: Set<DayOfWeek>)`, `Monthly(dayOfMonth: Int)`). Custom RRULE deferred to v2 (JSON schema is forward-compatible per §3 row 6). Schema cost identical to None-only; Phase 09 gets a real reminders feature surface.
- [x] **Search semantics** — **Lists only for v1.** Dashboard search field calls `ListsRepository.search(query)`, backed by `searchByName` LIKE-substring in `Lists.sq`. No `Items.sq` search query in v1. Item-level / cross-list search is a Phase 08 feature on the list-detail screen and gets its own repository surface then. FTS5 deferred either way.
- [x] **Delete UX semantics** — **5-second toast-undo.** Tap trash → row hides + 5-second undo toast. The state layer (Phase 05) holds the pending op; `softDelete()` commits when the toast dismisses, the UNDO action cancels before commit. Data layer never sees a "pending delete" status — same schema either way. Impacts Phase 07's dashboard wiring.
- [x] **Photo encoding** — **Re-encode to JPEG q=0.85, longest side ≤ 2048px.** `PhotoStorage.write()` re-encodes every capture; `photo.mime_type` will be `'image/jpeg'` in practice (column stays TEXT-typed for v2 flexibility). Cuts byte size 3–5×; sub-perceptible quality loss at item-thumbnail scale; strips EXIF metadata (privacy win for shared/backed-up DBs). Re-encoding lives in `platform-photo` (Phase 06); `:shared:data`'s `PhotosRepository.ingest(bytes, mime, w, h)` records whatever `platform-photo` produced — no encoding logic in the data layer.
- [x] **Default `sort_order` direction** — **Newest at top.** `ListsRepository.create(draft)` mints `sort_order = (currentMin - 1.0)`; first list gets `1.0`. Matches the design mockup ordering ("Supermarket" first, "Work Q4 Goals" last) and the notes-app / chat-app conventions. Same fractional-indexing pattern for items within a list (Phase 08 will confirm direction).

## 13. Hand-off checklist (gate to Phase 04)

- [ ] All checkboxes above ✅.
- [ ] `schema.sql` dump checked in; CI verification step green.
- [ ] Integration test runs green on Android JVM unit + iosSimulatorArm64 in CI.
- [ ] `MASTER_PLAN.md`: Phase 03 → 🟢, ▶ Next Step → Phase 04.
- [ ] `00_DECISIONS.md`: ADR-006 (a/b/c) accepted.
