package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import dev.franzueto.fluxit.shared.data.db.List as ListRow

/**
 * Phase 03 §4. Single shared factory that builds [FluxItDatabase] with every
 * column adapter from [Adapters.kt] wired in. Both platforms call this with
 * a freshly-constructed [SqlDriver] (production: from [DriverFactory]; tests:
 * in-memory via [inMemoryDriver]).
 *
 * Keeping driver creation and database wiring in separate seams (DriverFactory
 * → SqlDriver → FluxItDatabaseFactory → FluxItDatabase) means tests can swap
 * the driver while reusing the production adapter set without subclassing or
 * passing nulls.
 *
 * Boolean columns (`is_starred`, `is_completed`, `is_active`) are handled by
 * SQLDelight 2's built-in `INTEGER AS Boolean` codepath — no adapter needed.
 */
public fun fluxItDatabase(driver: SqlDriver): FluxItDatabase =
    FluxItDatabase(
        driver = driver,
        listAdapter =
            ListRow.Adapter(
                iconAdapter = IconNameAdapter,
                colorAdapter = ColorTokenAdapter,
                created_atAdapter = InstantAdapter,
                updated_atAdapter = InstantAdapter,
                deleted_atAdapter = InstantAdapter,
            ),
        itemAdapter =
            Item.Adapter(
                created_atAdapter = InstantAdapter,
                updated_atAdapter = InstantAdapter,
                deleted_atAdapter = InstantAdapter,
            ),
        reminderAdapter =
            Reminder.Adapter(
                owner_typeAdapter = OwnerTypeAdapter,
                fires_atAdapter = InstantAdapter,
                recurrenceAdapter = RecurrenceRuleAdapter,
                created_atAdapter = InstantAdapter,
                updated_atAdapter = InstantAdapter,
                deleted_atAdapter = InstantAdapter,
            ),
        photoAdapter =
            Photo.Adapter(
                created_atAdapter = InstantAdapter,
                deleted_atAdapter = InstantAdapter,
            ),
    )
