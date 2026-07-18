package dev.franzueto.fluxit.platform.reminders

import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import kotlinx.datetime.Instant

public data class ScheduledNotification(
    val title: String,
    val body: String,
    val deepLink: String,
    val firesAt: Instant,
    val recurrence: RecurrenceRule,
)

public fun ReminderOwner.deepLink(): String =
    when (this) {
        is ReminderOwner.OfList -> "fluxit://list/${listId.value}"
        is ReminderOwner.OfItem -> "fluxit://item/${itemId.value}"
    }

public fun Reminder.toScheduledNotification(): ScheduledNotification {
    val body =
        when (owner) {
            is ReminderOwner.OfList -> "You have a list reminder."
            is ReminderOwner.OfItem -> "You have an item reminder."
        }
    return ScheduledNotification(
        title = "Reminder",
        body = body,
        deepLink = owner.deepLink(),
        firesAt = firesAt,
        recurrence = recurrence,
    )
}
