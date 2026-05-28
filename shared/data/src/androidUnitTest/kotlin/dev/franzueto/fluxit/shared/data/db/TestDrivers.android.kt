package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

// JVM-side actual for the commonTest `expect fun inMemoryDriver()`. Builds
// a fresh in-memory SQLite via the SQLDelight JDBC driver and runs the
// schema migration so each test starts empty.
//
// `PRAGMA foreign_keys = ON` matches production (DriverFactory.android /
// .ios both enable FK enforcement). Without it the FK constraints in the
// .sq schemas are silently inert, so tests asserting FK-violation paths
// would pass against any code change.
internal actual fun inMemoryDriver(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FluxItDatabase.Schema.create(driver)
    driver.execute(identifier = null, sql = "PRAGMA foreign_keys = ON", parameters = 0)
    return driver
}
