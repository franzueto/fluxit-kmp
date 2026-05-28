package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver

/**
 * Per-test in-memory [SqlDriver] (Phase 03 §10). Each call builds a fresh
 * database with [FluxItDatabase.Schema] applied so tests start from a
 * known-empty state. Actual implementations:
 *
 * - androidUnitTest: `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` — JVM,
 *   ships with sqldelight-sqlite-driver.
 * - iosTest: `NativeSqliteDriver` with `DatabaseConfiguration.inMemory =
 *   true`, randomized name to avoid cross-test collisions.
 */
internal expect fun inMemoryDriver(): SqlDriver
