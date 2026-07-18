package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver

/**
 * - androidUnitTest: `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` — JVM,
 *   ships with sqldelight-sqlite-driver.
 * - iosTest: `NativeSqliteDriver` with `DatabaseConfiguration.inMemory =
 *   true`, randomized name to avoid cross-test collisions.
 */
internal expect fun inMemoryDriver(): SqlDriver
