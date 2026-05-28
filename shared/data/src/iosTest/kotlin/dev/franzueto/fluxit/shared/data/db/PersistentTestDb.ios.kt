package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random

@OptIn(ExperimentalForeignApi::class)
internal actual class PersistentTestDb actual constructor() {
    // sqliter uses `basePath` + `name` to locate the file. Each test gets
    // its own subdirectory under NSTemporaryDirectory so concurrent test
    // runs don't collide on the well-known filename.
    private val dirPath: String = NSTemporaryDirectory() + "fluxit-integration-${Random.nextLong()}/"
    private val dbName: String = "fluxit.db"

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(
            dirPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    actual fun openDriver(): SqlDriver =
        NativeSqliteDriver(
            schema = FluxItDatabase.Schema,
            name = dbName,
            onConfiguration = { config: DatabaseConfiguration ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(basePath = dirPath),
                )
            },
        )

    actual fun cleanup() {
        // Removes the entire test-scoped directory (DB + journal/WAL siblings).
        NSFileManager.defaultManager.removeItemAtPath(dirPath, error = null)
    }
}
