package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Lifecycle:
 *   ```
 *   val handle = PersistentTestDb()
 *   try {
 *       val d1 = handle.openDriver(); …; d1.close()
 *       val d2 = handle.openDriver(); …; d2.close()
 *   } finally {
 *       handle.cleanup()
 *   }
 *   ```
 *
 * Actuals:
 * - androidUnitTest: `jdbc:sqlite:<temp.db>` via `JdbcSqliteDriver`;
 *   `Schema.create()` runs only on first open (file-existence probe).
 * - iosTest: `NativeSqliteDriver` with `basePath = NSTemporaryDirectory()`
 *   subdir; sqliter's `user_version` pragma handles create / no-op
 *   internally on each construction.
 */
internal expect class PersistentTestDb() {
    /** Opens a fresh [SqlDriver] against the persistent backing store. */
    fun openDriver(): SqlDriver

    /** Deletes the backing file(s); call from `@AfterTest`. Safe to call once. */
    fun cleanup()
}
