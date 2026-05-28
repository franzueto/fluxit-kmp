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

- [x] `:shared:data/build.gradle.kts` applies `fluxit.kmp.library` + SQLDelight plugin.
- [x] Dependencies: `:shared:domain` (interfaces only), `kotlinx-coroutines-core`, `kotlinx-datetime`, `kotlinx-serialization-json`, SQLDelight runtime + coroutines extension + primitive adapters, Kermit.
- [x] No dependency on Android framework, AndroidX, UIKit, Foundation. Verified by Konsist (`DataLayerArchTest`).
- [x] iOS source set adds `app.cash.sqldelight:native-driver`; Android adds `app.cash.sqldelight:android-driver`; common defines `expect class DriverFactory`.
- [x] Database name constant in `:shared:data`: `"fluxit.db"` (production), `":memory:"` (test).

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
- [x] `selectAllActive` — `WHERE deleted_at IS NULL ORDER BY sort_order ASC`
- [x] `selectById(id)`
- [x] `searchByName(query)` — substring via `INSTR(LOWER(name), LOWER(:query)) > 0` (avoids LIKE `%`/`_` escape problem; resolved §12 row 2)
- [x] `insert(list)` (full row)
- [x] `updateMetadata(id, name, icon, color, is_starred, updated_at)`
- [x] `updateSortOrder(id, sort_order, updated_at)`
- [x] `softDelete(id, deleted_at)`
- [x] `hardDelete(id)` — used only by v2 sync compaction; not called in v1
- [x] `countActive`
- [x] `selectWithCounts` — joins to `item` to expose `total_items`, `completed_items`, `last_activity_at` for the dashboard

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
- [x] `selectByListGroupedByStatus` — single result set ordered by `(is_completed ASC, sort_order ASC)`; the §6 mapper partitions into TO BUY / COMPLETED (spec's "two logical groups" is the mapper output, not the SQL).
- [x] `selectById(id)` — for Edit Item screen.
- [x] `insert(item)`
- [x] `updateContent(id, title, subtitle, description, photo_id, updated_at)`
- [x] `setCompleted(id, is_completed, updated_at)`
- [x] `setStarred(id, is_starred, updated_at)`
- [x] `updateSortOrder(id, sort_order, updated_at)`
- [x] `softDelete(id, deleted_at)`
- [x] `softDeleteCompletedByList(list_id, deleted_at)` — backs the "Clear completed" action (Phase 08). **No `RETURNING id`:** §12 row 3 toast-undo holds cleared ids in state-layer memory during the 5s window, and `RETURNING` needs SQLite ≥ 3.35 which post-dates Android 26's bundled SQLite. Sidesteps bundling a newer driver.
- [x] `countByList(list_id)` and `countCompletedByList(list_id)` — drive the `13/20` progress bar.

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
- [x] `selectActiveByOwner(owner_type, owner_id)`
- [x] `selectAllUpcoming(now, limit)` — backs v2 Calendar tab (schema serves both phases without change).
- [x] `insert`, `updateFiresAt` (atomic with `:recurrence` per §5's reschedule contract), `setPlatformHandle`, `setActive`, `softDelete`.
- [x] `selectNeedingReschedule(now)` — recurring reminders past their last fire; used on app start to repopulate platform schedule.

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
- [x] `selectById(id)`
- [x] `insert`
- [x] `softDelete`
- [x] `selectOrphaned` — photos with no referencing `item` and `deleted_at` older than 24h; consumed by a janitor (§7). Ordered `deleted_at ASC` so oldest tombstones reap first.

### Schema versioning + migrations

- [x] `databaseVersion = 1` (SQLDelight 2 default) declared in Gradle `sqldelight { databases { … } }`.
- [ ] `migrations/` folder pre-created (empty for v1). _Deferred to §10 alongside the migration test harness — no migrations exist yet and an empty directory would be checked in for its own sake._
- [x] `schema.sql` dump produced by the hand-rolled `:shared:data:generateSchemaSql` task (SQLDelight's built-in `generateMainFluxItDatabaseSchema` produces a binary `.db`, not human-readable SQL); checked in at `shared/data/schema.sql`.
- [x] CI gate: `:shared:data:verifySchemaInSync` re-runs the generator in-memory and fails the build on drift; wired into `:shared:data:check`. Mirrors Phase 02's `verifyTokensInSync` / `verifyIconsInSync` pattern.
- [ ] Migration unit-test harness wired now (even with zero migrations) so adding migration #1 in v2 is mechanical. _Deferred to §10._

## 3. Column adapters

Living in `:shared:data/src/commonMain/.../db/Adapters.kt`:

- [x] `InstantAdapter` — `kotlinx.datetime.Instant` ↔ `Long` (epoch ms).
- [x] ~~`BooleanIntAdapter` — `Boolean` ↔ `Int (0/1)`~~. **Not needed:** SQLDelight 2 ships built-in `INTEGER AS Boolean` codegen — the three boolean columns (`is_starred`, `is_completed`, `is_active`) are handled directly by the row constructor, no `is_*Adapter` parameter exposed. Documented inline in `Adapters.kt`.
- [x] `OwnerTypeAdapter` — `enum class ReminderOwnerType { LIST, ITEM }` ↔ `String` via SQLDelight's `EnumColumnAdapter`.
- [x] `IconNameAdapter` — `enum class FluxItIconRef` ↔ `String` via `EnumColumnAdapter`. **Coupling direction reversed** from the original spec: per Phase 04 §2 (anticipated ADR-007a supersedes ADR-006c), domain owns the enum; the design system *consumes* it for icon registration. Enum lives at `shared/domain/.../model/FluxItIconRef.kt`.
- [x] `ColorTokenAdapter` — `enum class ColorToken` ↔ `String` (token keys: `PRIMARY_BLUE`, `ACCENT_ROSE`, `ACCENT_EMERALD`, `ACCENT_ORANGE`, `ACCENT_INDIGO`, `ACCENT_SKY`). Same coupling-reversal note as `IconNameAdapter`.
- [x] `RecurrenceRuleAdapter` — `RecurrenceRule` sealed `@Serializable` (`None / Daily / Weekly(daysOfWeek) / Monthly(dayOfMonth)`) ↔ JSON via `kotlinx.serialization`. v1 scope per resolved §12 row 1; `Custom(rrule)` deferred to v2 (JSON schema forward-compatible). Discriminator pinned to `"type"` + `ignoreUnknownKeys = true` for v2 reads.

## 4. Driver factories (expect/actual)

- [x] `commonMain`: `expect class DriverFactory { fun create(): SqlDriver }`
- [x] `androidMain`: `actual class DriverFactory(private val context: Context)` using `AndroidSqliteDriver`. Foreign keys ON, WAL ON.
- [x] `iosMain`: `actual class DriverFactory` using `NativeSqliteDriver`. Foreign keys ON.
- [x] `commonTest`: `expect fun inMemoryDriver(): SqlDriver` in `commonTest/.../db/TestDrivers.kt`; JVM actual uses `JdbcSqliteDriver(IN_MEMORY)` + manual `Schema.create()`; iOS actual uses `NativeSqliteDriver(name=..., onConfiguration={ inMemory = true })` (the native driver runs `Schema.create()` internally on construction). All five §3–§5 smoke tests promoted from `androidUnitTest` to `commonTest`; they execute against both targets via `:shared:data:test` (JVM) and `:shared:data:iosSimulatorArm64Test` (iOS).
- [x] Shared `fluxItDatabase(driver: SqlDriver): FluxItDatabase` factory in `commonMain` (`FluxItDatabaseFactory.kt`) wires every generated `Table.Adapter` with the §3 column adapters in one place. Both platforms call this with their `DriverFactory.create()` output. (Note: SQLDelight 2's generated `List` class needs an `import … as ListRow` alias to avoid the `kotlin.collections.List` clash — established pattern in the factory.)

## 5. Repository contracts (declared in `:shared:domain`, implemented here)

Domain ships **interfaces and entity DTOs**; data ships **implementations and DB row mappers**. Each operation that can fail returns `Result<T, DataError>` (sealed `DataError` with `NotFound`, `Conflict`, `Storage`, `Validation(field)`, `Unknown(cause)`).

- [x] **`ListsRepository`** — domain interface in `:shared:domain/.../repository/ListsRepository.kt`; impl `SqlListsRepository` in `:shared:data/.../repository/`. `Result<T, DataError>` realized as a sealed `Outcome<T, E>` in `:shared:domain/.../error/` (kotlin.Result requires Throwable; sealed Outcome keeps the typed-error contract). Reorder takes `previous: ListId?, next: ListId?` rather than a `Pair<ListId?, ListId?>` so call sites read positionally without unpacking. §8 sort-order rebalance ships in-repository: `selectActiveIdsBySortOrder` snapshot + `transactionWithResult` rewrite to evenly-spaced integers when bracket gap < 1e-9. _(Items + Reminders + Photos still open.)_
  - `fun observeAll(): Flow<List<ListSummary>>` (with counts; backs dashboard)
  - `fun observe(id: ListId): Flow<ListDetail?>`
  - `fun search(query: String): Flow<List<ListSummary>>` (debounce handled in state layer, not here)
  - `suspend fun create(draft: ListDraft): Outcome<ListId, DataError>`
  - `suspend fun rename(id, name): Outcome<Unit, DataError>`
  - `suspend fun updateAppearance(id, icon, color): Outcome<Unit, DataError>`
  - `suspend fun setStarred(id, starred): Outcome<Unit, DataError>`
  - `suspend fun reorder(id, previous: ListId?, next: ListId?): Outcome<Unit, DataError>` (fractional sort under the hood)
  - `suspend fun delete(id): Outcome<Unit, DataError>` (soft)
- [x] **`ItemsRepository`** — domain interface in `:shared:domain/.../repository/ItemsRepository.kt`; impl `SqlItemsRepository` in `:shared:data/.../repository/`. `ItemPatch` is a full content replacement (single backing UPDATE statement writes all four mutable columns atomically — callers that want "leave subtitle alone" read the current item and re-emit). `add()` pre-checks parent list existence in a transaction (`selectListIsActive`) so missing-list surfaces as typed `NotFound` instead of a driver FK exception. `clearCompleted()` returns the count inside a transaction (`countCompletedByList` → `softDeleteCompletedByList`) so the state layer's 5-second toast-undo flow has the cleared-count for messaging. §8 per-list rebalance: same midpoint-collapse trigger as Lists, scoped via `selectActiveIdsByListBySortOrder(list_id)` so a hot list doesn't disturb the others. `PhotoId` value class pulled forward into `ItemEntities.kt` (Item references Photo) so the Photos slice doesn't need to retrofit it.
  - `fun observeByList(listId): Flow<ItemsSection>` — `ItemsSection = (active, completed, total, completedCount)`; built from `selectByListGroupedByStatus` in one Flow emission via `partition { isCompleted }`.
  - `fun observe(itemId): Flow<Item?>`
  - `suspend fun add(listId, draft: ItemDraft): Outcome<ItemId, DataError>`
  - `suspend fun update(itemId, patch: ItemPatch): Outcome<Unit, DataError>`
  - `suspend fun setCompleted(itemId, completed): Outcome<Unit, DataError>`
  - `suspend fun setStarred(itemId, starred): Outcome<Unit, DataError>`
  - `suspend fun reorder(itemId, previous: ItemId?, next: ItemId?): Outcome<Unit, DataError>`
  - `suspend fun delete(itemId): Outcome<Unit, DataError>` (soft)
  - `suspend fun clearCompleted(listId): Outcome<Int, DataError>`
- [x] **`RemindersRepository`** — domain interface in `:shared:domain/.../repository/RemindersRepository.kt`; impl `SqlRemindersRepository` in `:shared:data/.../repository/`. `observeForOwner` takes a typed `ReminderOwner` sealed (`OfList(ListId) | OfItem(ItemId)`) rather than the spec's `(ownerType, ownerId)` pair — Phase 04 §3's anticipated wrapper landed here so use cases never juggle the raw discriminator. `ReminderScheduler` port deliberately NOT injected at this slice: the repo is row-only (platform scheduling stays in Phase 06's `:platform:platform-reminders`, which observes `observeForOwner` and writes back via `rebindPlatformHandle`). `RecurrenceRule.None ↔ NULL` collapse at the storage edge per §3 contract (mapper reinflates so domain code never special-cases null). Reminders.sq gains `selectById` (placed after `selectActiveByOwner` so the DDL block of `schema.sql` stays untouched — `verifySchemaInSync` reads everything before the first query label as DDL).
  - `fun observeForOwner(owner: ReminderOwner): Flow<List<Reminder>>`
  - `fun observeUpcoming(limit): Flow<List<Reminder>>` (now-snapshot at subscription; v2 Calendar will need a wall-clock ticker)
  - `suspend fun schedule(spec: ReminderSpec): Outcome<ReminderId, DataError>`
  - `suspend fun reschedule(id, firesAt, recurrence): Outcome<Unit, DataError>`
  - `suspend fun cancel(id): Outcome<Unit, DataError>` (soft-delete; Phase 06 reaps platform handles)
  - `suspend fun rebindPlatformHandle(id, handle): Outcome<Unit, DataError>`
- [x] **`PhotosRepository`** — domain interface in `:shared:domain/.../repository/PhotosRepository.kt`; impl `SqlPhotosRepository` in `:shared:data/.../repository/`. §7 `PhotoStorage` port declared at `:shared:domain/.../port/PhotoStorage.kt` (Phase 06 ships `:platform:platform-photo` impl). `ingest()` honors the §7 crash-safety order: file write → row insert → on-insert-failure file cleanup (`runCatching { storage.delete(path) }`). Cancellation mid-insert also triggers cleanup before rethrow so the file doesn't linger as a guaranteed orphan. `deleteIfOrphaned()` no-ops (returns Ok) when a live item still references the photo — the §7 janitor reruns on the 24h cycle and reaps once references drop. Photos.sq gains `selectHasLiveReference` (cross-table to `item`; placed after `selectOrphaned` so DDL block stays untouched).
  - `suspend fun ingest(bytes, mime, width, height): Outcome<PhotoId, DataError>`
  - `fun observe(photoId): Flow<Photo?>`
  - `suspend fun deleteIfOrphaned(photoId): Outcome<Unit, DataError>` (Ok no-op when referenced)

## 6. Mappers

- [x] One mapper file per table: `ListMapper`, `ItemMapper`, `ReminderMapper`, `PhotoMapper` (all four landed in their respective §5 slices).
- [x] Mappers are **pure** (no IO, no `Clock`). Time injection uses `kotlinx.datetime.Clock` passed in from repository constructors so tests use a `FakeClock`.
- [x] `ListSummary` derived in SQL via the `selectWithCounts` query — *not* by combining two flows in Kotlin (single source of truth, fewer recompositions).

## 7. Photo file storage contract

- [x] `PhotoStorage` port (declared at `:shared:domain/.../port/PhotoStorage.kt`; impl deferred to Phase 06's `:platform:platform-photo`). `RelativePath` is `String` for v1 (typed wrapper would be over-engineering at one call site; can lift later).
  - `suspend fun write(bytes, mime): String`
  - `suspend fun read(relativePath): ByteArray?`
  - `suspend fun delete(relativePath): Boolean`
  - `fun resolveAbsolute(relativePath): String`
- [x] **Photo janitor**: `selectOrphaned` query and `deleteIfOrphaned` repo method exposed. `PhotoJanitor` use case proper still belongs to Phase 04 (no behavior change there).
- [x] **Crash safety**: `SqlPhotosRepository.ingest()` writes file first; on row insert exception OR cancellation, runs `runCatching { storage.delete(path) }` before returning/rethrow. Smoke test pins the IdGenerator to force a duplicate-PK insert failure and asserts cleanup ran.

## 8. Sort order strategy (fractional indexing)

- [x] Sort orders are `REAL` doubles. New row defaults to `(prev.sort_order + next.sort_order) / 2.0`; first row gets `1.0`. Newest-at-top minting per §12 row 5: `create()` reads `MIN(sort_order)` and mints `min - 1.0` (Lists global flavor + Items per-list flavor; see Lists.sq `selectMinActiveSortOrder` / Items.sq `selectMinActiveSortOrderByList`).
- [x] When the gap between two adjacent rows shrinks below `1e-9`, the repository runs an in-Kotlin rebalance inside a transaction: `selectActiveIdsBySortOrder` (Lists) / `selectActiveIdsByListBySortOrder` (Items) → rewrite every row's `sort_order` to evenly-spaced integers (1.0, 2.0, ...). No SQL function — the per-row UPDATE pattern matches the rest of the repo and keeps DDL minimal. Implemented at `SqlListsRepository.rebalanceSortOrders()` + `SqlItemsRepository.rebalanceSortOrders(listId)`.
- [ ] Unit test: 1000 random reorders never produce gap collapse without compaction kicking in. _Deferred to §10 — current Lists + Items smoke tests cover "rebalance fires under 100 forced collapses without exception"; the 1000-random scenario fits the §10 fuzz/property-test bucket._

## 9. ID generation

- [x] Use **UUID v4** strings (lowercase, no braces). `expect fun newId(): String` in `:core:core-utils` with `actual` per platform:
  - Android: `java.util.UUID.randomUUID().toString()`
  - iOS: `NSUUID().UUIDString().lowercase()`
  - Plus `IdGenerator` fun-interface seam (`IdGenerator.System` binds to `::newId`) so repositories can inject the abstraction; tests pass `FakeIdGenerator` returning UUID-v4-shaped strings.
- [x] Tested via Konsist: `DataLayerArchTest` rule "entity ids are minted via newId() / IdGenerator, not Random or Clock-derived sources" bans `Random.nextLong/Int/Bytes`, `currentTimeMillis`, `Instant.toEpochMilliseconds`, and `.hashCode()` as id sources outside `Ids.*` files and test source sets.

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

- [x] **ADR-006** — SQLDelight 2 over Room KMP. **Status: Proposed** (flips to Accepted at §13 hand-off; all status-flip preconditions are now satisfied — schema landed, `verifySchemaInSync` green, dump checked in. Migration test harness is the one remaining precondition, lands with §10.)
- [x] **ADR-006a** — UUID v4 string ids (vs. ULID, vs. autoincrement int). **Status: Proposed** (flip preconditions satisfied: `newId()` actuals shipped, Konsist banned-source rule active. Flips at §13.)
- [x] **ADR-006b** — Soft-delete + tombstones from day one. **Status: Proposed.**
- [x] **ADR-006c** — Coupling `:shared:data` to `:core:core-designsystem` for the `IconNameAdapter` enum. **Status: Proposed but coupling direction reversed.** Phase 04 §2 supersedes the original direction — domain owns `FluxItIconRef` and `ColorToken`; designsystem consumes them. ADR-007a (anticipated in Phase 04) formalizes the reversal. ADR-006c will be marked **Superseded by ADR-007a** at §13 rather than Accepted; its rationale (icon names are product domain, not chrome) is preserved by 007a.

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

- [ ] All checkboxes above ✅. _(Progress as of 2026-05-27: §1, §2, §3, §4 (modulo iOS test driver), §5 (all four repos), §6 (all four mappers), §7 (port + crash safety), §8 (rebalance), §9, §11 drafts, §12 done; §10 test pyramid still open — that's the §13 gating item.)_
- [x] `schema.sql` dump checked in; CI verification step (`verifySchemaInSync` wired into `:shared:data:check`) green.
- [ ] Integration test runs green on Android JVM unit + iosSimulatorArm64 in CI. _(§10 deliverable.)_
- [ ] `MASTER_PLAN.md`: Phase 03 → 🟢, ▶ Next Step → Phase 04.
- [ ] `00_DECISIONS.md`: ADR-006 / 006a / 006b accepted; ADR-006c marked **Superseded by ADR-007a** (Phase 04).

---

## Implementation log (chronological, for traceability across sessions)

Latest-on-top. Each entry: `YYYY-MM-DD — short summary` + the commit SHA(s)
the entry corresponds to. Keep brief; the rich detail lives in commit bodies.

- **2026-05-28** — §10 foundation: `expect fun inMemoryDriver()` in
  `commonTest`; JVM actual reuses the prior JdbcSqliteDriver helper;
  iOS actual uses `NativeSqliteDriver` with
  `DatabaseConfiguration.inMemory = true` and a randomized name. All
  five smoke test files (`FluxItDatabaseFactorySmokeTest` + four
  `Sql*RepositorySmokeTest`) moved from `androidUnitTest` → `commonTest`;
  `"...%012d".format(n)` calls replaced with `padStart(12, '0')` for
  KMP-common compatibility. 45/45 tests pass on both
  `:shared:data:testDebugUnitTest` (JVM) and
  `:shared:data:iosSimulatorArm64Test` (iOS). _Commit `2c3673f`._
- **2026-05-27** — §5 Photos slice (4/4) — closes §5. `Photo` entity in
  `:shared:domain/.../model/Photo.kt`; `PhotoStorage` port at
  `:shared:domain/.../port/PhotoStorage.kt` (impl deferred to Phase 06);
  `PhotosRepository` interface + `SqlPhotosRepository` impl +
  `PhotoMapper` in `:shared:data`. Photos.sq gains
  `selectHasLiveReference` for `deleteIfOrphaned`'s pre-check. §7
  crash-safety implemented: file write → row insert → on insert
  failure OR coroutine cancellation, `runCatching { storage.delete(path) }`
  cleans up so a crash leaks at most one orphan file (the janitor
  reaps via the 24h grace window). 6 smoke tests: ingest happy path,
  ingest cleanup on PK collision, observe-after-delete, deleteIfOrphaned
  no-op while referenced, deleteIfOrphaned soft-deletes after reference
  removed, NotFound for unknown id. _Commit `963817a`._
- **2026-05-27** — §5 Reminders slice (3/4): `ReminderId`, `ReminderOwner`
  sealed (`OfList | OfItem`), `Reminder`, `ReminderSpec` entities in
  `:shared:domain`; `RemindersRepository` interface +
  `SqlRemindersRepository` impl + `ReminderMapper` in `:shared:data`.
  Reminders.sq gains `selectById` for repo pre-checks (placed after
  `selectActiveByOwner` so DDL portion stays untouched).
  `RecurrenceRule.None ↔ NULL` collapse handled in repo + mapper;
  platform scheduling deferred to Phase 06 (no `ReminderScheduler` port
  injected yet, per spec). 10 smoke tests cover schedule + recurrence
  round-trip + None-as-null + scoped owner observe + upcoming +
  reschedule + cancel + rebindPlatformHandle (set/clear) +
  not-found paths + item-owner round-trip. _Commit `cb1c0eb`._
- **2026-05-27** — §5 Items slice (2/4): `ItemId`, `PhotoId`, `Item`,
  `ItemDraft`, `ItemPatch`, `ItemsSection` entities in `:shared:domain`;
  `ItemsRepository` interface + `SqlItemsRepository` impl + `ItemMapper` in
  `:shared:data`. Items.sq gains `selectMinActiveSortOrderByList`,
  `selectActiveSortOrder`, `selectActiveIdsByListBySortOrder`,
  `selectListIsActive`. §8 per-list rebalance ships with this slice
  (mirrors the Lists global one, scoped by `list_id`). 14 smoke tests
  cover add (with parent-list NotFound + title validation) /
  observeByList partition / setCompleted section-swap / update / delete /
  setStarred / reorder / clearCompleted / rebalance trigger.
  _Commit `04f441b`._
- **2026-05-27** — §5 Lists slice: `Outcome<T, E>` + `DataError` taxonomy +
  `ListId`/`ListDraft`/`ListSummary`/`ListDetail` entities in `:shared:domain`;
  `ListsRepository` interface + `SqlListsRepository` impl + `ListMapper` in
  `:shared:data`. §8 fractional rebalance trigger ships with this slice
  (rebalance fires when bracket gap < 1e-9; full per-list compaction lands
  with Items in the next slice). Lists.sq gains `updateName`,
  `updateAppearance`, `updateStarred`, `selectMinActiveSortOrder`,
  `selectActiveSortOrder`, `selectActiveIdsBySortOrder`. JVM smoke test
  covers create/rename/setStarred/reorder/delete/search + validation +
  not-found + rebalance trigger. _Commit `7d1d15d`._
- **2026-05-26** — §3 adapters + §4 shared `fluxItDatabase(driver)` factory +
  JVM-side in-memory test driver + smoke test. AS clauses on all four `.sq`
  tables; `schema.sql` regenerated. `BooleanIntAdapter` omitted (SQLDelight 2
  built-in). iOS test driver deferred to §10. _Commit `2b151f3`._
- **2026-05-26** — Domain seed pulled forward from Phase 04 §2: `ColorToken`,
  `FluxItIconRef`, `ReminderOwnerType` enums + `RecurrenceRule` sealed
  `@Serializable`. Wires `:shared:domain` commonMain + `kotlinx-datetime` +
  `kotlinx-serialization-core`. _Commit `9421fb1`._
- **2026-05-26** — §9 `newId()` expect/actual in `:core:core-utils` (UUID v4
  via JVM `UUID.randomUUID()` and iOS `NSUUID().UUIDString().lowercase()`) +
  `IdGenerator` fun-interface seam + Konsist banned-id-source rule in
  `DataLayerArchTest`. _Commit `4a798ff`._
- **2026-05-19** — §12 open questions resolved (recurrence v1 set; lists-only
  search; 5s toast-undo delete; JPEG q=0.85 max-dim 2048 photo encoding;
  newest-at-top sort_order). _Commit `90130a7`._
- **2026-05-19** — ADR-006 / 006a / 006b / 006c drafted as Proposed.
  _Commits `675487d` (006), `349ee15` (006a), `3ac2a9b` (006b), `6f2abbb`
  (006c)._
- **2026-05-19** — §2D Photos.sq + Items.photo_id FK retrofit + schema task
  ordering fix. Phase 03 §2 (Schema) complete. _Commit `36b2ce4`._
- **2026-05-19** — §2C Reminders.sq with polymorphic owner. _Commit `221aa19`._
- **2026-05-19** — §2B Items.sq + Lists.sq `selectWithCounts` retrofit.
  _Commit `e5589c7`._
- **2026-05-19** — §2A Lists.sq + hand-rolled `generateSchemaSql` /
  `verifySchemaInSync` pipeline. _Commit `1562b39`._
- **2026-05-19** — §1 `:shared:data` wired (SQLDelight plugin, deps,
  DriverFactory expect/actual) + Konsist arch tests (`DataLayerArchTest`).
  _Commits `78801bd`, `e6e9f67`._
