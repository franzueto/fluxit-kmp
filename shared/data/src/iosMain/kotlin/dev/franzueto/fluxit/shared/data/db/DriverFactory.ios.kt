package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

actual class DriverFactory {
    actual fun create(): SqlDriver =
        NativeSqliteDriver(
            schema = FluxItDatabase.Schema,
            name = DatabaseName.PRODUCTION,
            onConfiguration = { config: DatabaseConfiguration ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true),
                )
            },
        )
}
