package dev.franzueto.fluxit.shared.data.mapper

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderOwnerType
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.franzueto.fluxit.shared.data.db.Reminder as ReminderRow

class ReminderMapperTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Test
    fun list_owner_row_maps_to_OfList() {
        val row =
            ReminderRow(
                id = "00000000-0000-4000-8000-000000000020",
                owner_type = ReminderOwnerType.LIST,
                owner_id = "00000000-0000-4000-8000-000000000001",
                fires_at = now,
                recurrence = RecurrenceRule.Daily,
                platform_handle = "wm-42",
                is_active = true,
                created_at = now,
                updated_at = now,
                deleted_at = null,
            )

        val mapped = row.toDomain()
        assertEquals(ReminderId("00000000-0000-4000-8000-000000000020"), mapped.id)
        assertEquals(
            ReminderOwner.OfList(ListId("00000000-0000-4000-8000-000000000001")),
            mapped.owner,
        )
        assertEquals(RecurrenceRule.Daily, mapped.recurrence)
        assertEquals("wm-42", mapped.platformHandle)
    }

    @Test
    fun item_owner_row_maps_to_OfItem() {
        val row =
            ReminderRow(
                id = "00000000-0000-4000-8000-000000000021",
                owner_type = ReminderOwnerType.ITEM,
                owner_id = "00000000-0000-4000-8000-000000000010",
                fires_at = now,
                recurrence = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY)),
                platform_handle = null,
                is_active = true,
                created_at = now,
                updated_at = now,
                deleted_at = null,
            )

        val mapped = row.toDomain()
        assertEquals(
            ReminderOwner.OfItem(ItemId("00000000-0000-4000-8000-000000000010")),
            mapped.owner,
        )
        assertEquals(RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY)), mapped.recurrence)
    }

    @Test
    fun null_recurrence_reinflates_to_None_sentinel() {
        // §3 storage contract: None ≡ NULL on the wire. The mapper must
        // hand back the explicit sentinel so domain code never has to
        // pattern-match null vs. None.
        val row =
            ReminderRow(
                id = "00000000-0000-4000-8000-000000000022",
                owner_type = ReminderOwnerType.LIST,
                owner_id = "00000000-0000-4000-8000-000000000001",
                fires_at = now,
                recurrence = null,
                platform_handle = null,
                is_active = true,
                created_at = now,
                updated_at = now,
                deleted_at = null,
            )

        assertEquals(RecurrenceRule.None, row.toDomain().recurrence)
    }
}
