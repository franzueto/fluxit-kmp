package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 03 §10 row (d) — migration test harness. v1 ships with **zero**
 * migration files; the harness still exists so that adding migration #1
 * (whenever v2 needs to alter the schema) is mechanical:
 *
 * **Convention for adding migration #N (v2+):**
 * 1. Drop `N.sqm` in `shared/data/src/commonMain/sqldelight/.../migrations/`
 *    containing the `ALTER TABLE` / etc. statements.
 * 2. Bump `databaseVersion` in `:shared:data` Gradle config from N-1 → N.
 * 3. Update the affected `.sq` table definitions to reflect the new
 *    schema; regenerate `schema.sql` via
 *    `./gradlew :shared:data:generateSchemaSql`.
 * 4. Add a per-migration test to this file:
 *    ```
 *    @Test fun migration_to_v2_adds_priority_column() {
 *        val driver = inMemoryDriver()
 *        FluxItDatabase.Schema.migrate(driver, oldVersion = 1L, newVersion = 2L)
 *        // assert the new column exists, old data round-trips, etc.
 *    }
 *    ```
 *    The harness below already pins the v1 baseline so a regression in
 *    the migrate() call path surfaces at PR time, not at user-update
 *    time.
 *
 * The three tests below cover the v1 invariants:
 *   - schema version stays at 1 (changing it without a migration is a bug);
 *   - `Schema.create()` succeeds on a fresh in-memory driver;
 *   - `Schema.migrate(driver, 1, 1)` is a no-op (proves the SQLDelight
 *     migration codepath at least executes — the same call site v2 will
 *     use with `oldVersion < newVersion`).
 */
class MigrationHarnessTest {
    @Test
    fun schema_version_is_pinned_to_1_for_v1() {
        // If this assertion ever fails, a `databaseVersion` bump landed
        // without a corresponding `<N>.sqm` migration file — the
        // convention in the class KDoc is the fix.
        assertEquals(1L, FluxItDatabase.Schema.version)
    }

    @Test
    fun schema_create_succeeds_on_a_fresh_driver() {
        // inMemoryDriver() already calls Schema.create() on construction,
        // so the helper returning without throwing is the assertion.
        val driver = inMemoryDriver()
        // Sanity-poke a query to confirm the schema actually exists.
        assertEquals(0L, fluxItDatabase(driver).listsQueries.countActive().executeAsOne())
    }

    @Test
    fun migrate_from_current_to_current_version_is_a_no_op() {
        // SQLDelight's Schema.migrate runs every `<N>.sqm` for N in
        // (oldVersion, newVersion]. With oldVersion == newVersion the
        // range is empty → no-op. This test proves the migrate() call
        // path is wired and doesn't throw on the boundary case v2's
        // first real migration will exercise.
        val driver = inMemoryDriver()
        val result = FluxItDatabase.Schema.migrate(driver, oldVersion = 1L, newVersion = 1L)
        // SQLDelight 2 returns QueryResult<Unit>; .value on QueryResult.Value
        // unwraps without throwing.
        assertEquals(QueryResult.Unit, result)
    }
}
