package dev.franzueto.fluxit.platform.reminders

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledNotificationTest {
    private fun reminder(owner: ReminderOwner) =
        Reminder(
            id = ReminderId("r1"),
            owner = owner,
            firesAt = Instant.fromEpochMilliseconds(1_000_000),
            recurrence = RecurrenceRule.None,
            platformHandle = null,
            isActive = true,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    @Test
    fun list_reminder_maps_to_list_deep_link() {
        val n = reminder(ReminderOwner.OfList(ListId("abc"))).toScheduledNotification()
        assertEquals("fluxit://list/abc", n.deepLink)
        assertEquals("Reminder", n.title)
    }

    @Test
    fun item_reminder_maps_to_item_deep_link() {
        val n = reminder(ReminderOwner.OfItem(ItemId("xyz"))).toScheduledNotification()
        assertEquals("fluxit://item/xyz", n.deepLink)
    }

    @Test
    fun carries_fire_instant_and_recurrence_through() {
        val r = reminder(ReminderOwner.OfList(ListId("abc")))
        val n = r.toScheduledNotification()
        assertEquals(r.firesAt, n.firesAt)
        assertEquals(RecurrenceRule.None, n.recurrence)
    }
}
