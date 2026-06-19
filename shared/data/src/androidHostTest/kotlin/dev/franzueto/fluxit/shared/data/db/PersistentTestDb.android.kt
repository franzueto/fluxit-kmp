package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Path

internal actual class PersistentTestDb actual constructor() {
    // Files.createTempFile reserves a unique path, but JdbcSqliteDriver
    // needs to see a non-existent file the first time so its own connection
    // bootstrap can initialize the SQLite header. Delete the placeholder
    // immediately; first openDriver() creates a real DB at that path, and
    // subsequent openDriver() calls reopen the same file.
    private val file: Path =
        Files.createTempFile("fluxit-integration-", ".db").also { Files.deleteIfExists(it) }

    actual fun openDriver(): SqlDriver {
        val firstOpen = !Files.exists(file)
        val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
        if (firstOpen) {
            FluxItDatabase.Schema.create(driver)
        }
        // Foreign-key enforcement is per-connection in SQLite, so it has to
        // run on every reopen, not just the first. Matches DriverFactory's
        // production wiring.
        driver.execute(identifier = null, sql = "PRAGMA foreign_keys = ON", parameters = 0)
        return driver
    }

    actual fun cleanup() {
        Files.deleteIfExists(file)
    }
}
