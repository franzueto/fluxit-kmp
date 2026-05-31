package dev.franzueto.fluxit.platform.reminders

import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import kotlinx.datetime.Instant

/**
 * Vendor-agnostic projection of a [Reminder] into the fields a platform notifier
 * needs (plan/06 §5). Both `AndroidReminderScheduler` and `IosReminderScheduler`
 * build their native request from this — keeping the recurrence→native-trigger
 * mapping the only platform-specific reminder logic.
 *
 * **Content gap (documented):** the [ReminderScheduler][dev.franzueto.fluxit.shared.domain.port.ReminderScheduler]
 * port carries only a [Reminder], which holds **no** list/item name (plan/06 §5
 * envisioned the use case composing title/body from names before calling the
 * scheduler, but the port signature passes none). So v1 uses a generic [title] +
 * owner-shaped [body]; enriching with real names needs the port to carry text
 * (or a content lookup) — tracked as a Phase 06 follow-up.
 */
public data class ScheduledNotification(
    val title: String,
    val body: String,
    val deepLink: String,
    val firesAt: Instant,
    val recurrence: RecurrenceRule,
)

/** `fluxit://list/{id}` / `fluxit://item/{id}` deep link for [owner] (plan/06 §5). */
public fun ReminderOwner.deepLink(): String =
    when (this) {
        is ReminderOwner.OfList -> "fluxit://list/${listId.value}"
        is ReminderOwner.OfItem -> "fluxit://item/${itemId.value}"
    }

/** Projects a [Reminder] to the platform-agnostic [ScheduledNotification] (plan/06 §5). */
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
