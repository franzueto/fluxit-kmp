package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

// JVM-side actual for the commonTest `expect fun inMemoryDriver()`. Builds
// a fresh in-memory SQLite via the SQLDelight JDBC driver and runs the
// schema migration so each test starts empty.
internal actual fun inMemoryDriver(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FluxItDatabase.Schema.create(driver)
    return driver
}
