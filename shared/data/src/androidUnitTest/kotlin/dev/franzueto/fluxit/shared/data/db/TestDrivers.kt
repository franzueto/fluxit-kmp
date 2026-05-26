package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

// JVM-side in-memory SQLite driver for unit tests. Builds a fresh DB and runs
// FluxItDatabase.Schema.create() per call so each test starts from a known
// empty state. iOS-side helper lands with Phase 03 §10.
internal fun inMemoryDriver(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FluxItDatabase.Schema.create(driver)
    return driver
}
