package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import kotlin.random.Random

// iOS-side actual for the commonTest `expect fun inMemoryDriver()`. The
// native SQLite driver doesn't expose a JDBC-style `:memory:` URI — we go
// through DatabaseConfiguration.inMemory = true instead. The `name` is
// still required (it'd be the on-disk filename otherwise) but is ignored
// when `inMemory` is set; randomizing it dodges any cached-driver collision
// across tests in the same process.
internal actual fun inMemoryDriver(): SqlDriver =
    NativeSqliteDriver(
        schema = FluxItDatabase.Schema,
        name = "test-${Random.nextLong()}.db",
        onConfiguration = { config: DatabaseConfiguration ->
            config.copy(inMemory = true)
        },
    )
