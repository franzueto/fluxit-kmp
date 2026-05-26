package dev.franzueto.fluxit.shared.data.db

import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderOwnerType
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Smoke test for Phase 03 §3 (adapters) + §4 (FluxItDatabase factory).
//
// Inserts one row in each of the four tables, reads it back, and asserts the
// adapter round-trips preserve type identity end-to-end. Not a replacement
// for §10's per-query test suite — just proves the wiring works before §5
// builds repositories on top of it.
class FluxItDatabaseFactorySmokeTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Test
    fun adapters_roundtrip_a_list_row() {
        val db = fluxItDatabase(inMemoryDriver())

        db.listsQueries.insert(
            id = "00000000-0000-4000-8000-000000000001",
            name = "Supermarket",
            icon = FluxItIconRef.CART,
            color = ColorToken.PRIMARY_BLUE,
            is_starred = true,
            sort_order = 1.0,
            created_at = now,
            updated_at = now,
        )

        val row = db.listsQueries.selectById("00000000-0000-4000-8000-000000000001").executeAsOne()
        assertEquals("Supermarket", row.name)
        assertEquals(FluxItIconRef.CART, row.icon)
        assertEquals(ColorToken.PRIMARY_BLUE, row.color)
        assertTrue(row.is_starred)
        assertEquals(now, row.created_at)
        assertEquals(now, row.updated_at)
    }

    @Test
    fun adapters_roundtrip_an_item_row() {
        val db = fluxItDatabase(inMemoryDriver())
        val listId = "00000000-0000-4000-8000-000000000010"
        db.listsQueries.insert(
            id = listId,
            name = "Supermarket",
            icon = FluxItIconRef.CART,
            color = ColorToken.PRIMARY_BLUE,
            is_starred = false,
            sort_order = 1.0,
            created_at = now,
            updated_at = now,
        )

        db.itemsQueries.insert(
            id = "00000000-0000-4000-8000-000000000011",
            list_id = listId,
            title = "Milk",
            subtitle = "Whole, 1L",
            description = null,
            is_completed = false,
            is_starred = false,
            photo_id = null,
            sort_order = 1.0,
            created_at = now,
            updated_at = now,
        )

        val row = db.itemsQueries.selectById("00000000-0000-4000-8000-000000000011").executeAsOne()
        assertEquals("Milk", row.title)
        assertEquals("Whole, 1L", row.subtitle)
        assertEquals(false, row.is_completed)
        assertEquals(false, row.is_starred)
        assertEquals(now, row.updated_at)
    }

    @Test
    fun adapters_roundtrip_a_reminder_row_with_weekly_recurrence() {
        val db = fluxItDatabase(inMemoryDriver())
        val recurrence: RecurrenceRule =
            RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))

        db.remindersQueries.insert(
            id = "00000000-0000-4000-8000-000000000020",
            owner_type = ReminderOwnerType.LIST,
            owner_id = "00000000-0000-4000-8000-000000000010",
            fires_at = now,
            recurrence = recurrence,
            platform_handle = null,
            is_active = true,
            created_at = now,
            updated_at = now,
        )

        val row =
            db.remindersQueries
                .selectActiveByOwner(ReminderOwnerType.LIST, "00000000-0000-4000-8000-000000000010")
                .executeAsOne()
        assertEquals(ReminderOwnerType.LIST, row.owner_type)
        assertEquals(now, row.fires_at)
        assertEquals(recurrence, row.recurrence)
        assertTrue(row.is_active)
    }

    @Test
    fun adapters_roundtrip_a_photo_row() {
        val db = fluxItDatabase(inMemoryDriver())

        db.photosQueries.insert(
            id = "00000000-0000-4000-8000-000000000030",
            relative_path = "photos/2026/05/26/abc.jpg",
            mime_type = "image/jpeg",
            width_px = 2048,
            height_px = 1536,
            byte_size = 524_288,
            created_at = now,
        )

        val row = db.photosQueries.selectById("00000000-0000-4000-8000-000000000030").executeAsOne()
        assertEquals("photos/2026/05/26/abc.jpg", row.relative_path)
        assertEquals("image/jpeg", row.mime_type)
        assertEquals(2048L, row.width_px)
        assertEquals(now, row.created_at)
        assertNotNull(row)
    }
}
