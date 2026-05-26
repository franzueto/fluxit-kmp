package dev.franzueto.fluxit.shared.data.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderOwnerType
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

// Phase 03 §3. Column adapters that map domain types to/from SQLite's native
// storage classes at the SQLDelight boundary. Domain side stays pure; data
// side encodes/decodes at the table edge.
//
// All six adapters are pure (no IO, no Clock). Adapter instances are
// stateless — the JSON adapter holds one `Json` configuration to avoid
// re-instantiation per row, but it's effectively a const.
//
// Wired into the generated `FluxItDatabase(driver, …Adapter)` constructor
// by `FluxItDatabaseFactory` (sibling file).

internal object InstantAdapter : ColumnAdapter<Instant, Long> {
    override fun decode(databaseValue: Long): Instant = Instant.fromEpochMilliseconds(databaseValue)

    override fun encode(value: Instant): Long = value.toEpochMilliseconds()
}

// NB: SQLDelight 2 ships built-in `INTEGER AS Boolean` codegen, so no
// adapter is needed for the three boolean columns (is_starred, is_completed,
// is_active). The generated Adapter classes don't expose `is_*Adapter`
// parameters at all — the conversion is inlined in the row constructor.

internal val OwnerTypeAdapter: ColumnAdapter<ReminderOwnerType, String> =
    EnumColumnAdapter()

internal val IconNameAdapter: ColumnAdapter<FluxItIconRef, String> =
    EnumColumnAdapter()

internal val ColorTokenAdapter: ColumnAdapter<ColorToken, String> =
    EnumColumnAdapter()

// JSON shape for RecurrenceRule is part of the storage contract (ADR-006).
// `ignoreUnknownKeys = true` future-proofs reads against a v2 variant that
// adds a field; `classDiscriminator = "type"` pins the discriminator name
// so renaming the kotlinx-serialization default later is a no-op for us.
private val recurrenceJson: Json =
    Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

internal object RecurrenceRuleAdapter : ColumnAdapter<RecurrenceRule, String> {
    override fun decode(databaseValue: String): RecurrenceRule = recurrenceJson.decodeFromString(RecurrenceRule.serializer(), databaseValue)

    override fun encode(value: RecurrenceRule): String = recurrenceJson.encodeToString(RecurrenceRule.serializer(), value)
}
