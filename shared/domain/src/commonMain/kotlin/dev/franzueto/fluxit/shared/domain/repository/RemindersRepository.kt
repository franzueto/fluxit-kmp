package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Persistence contract for reminders (Phase 03 §5). The data layer only
 * writes the row; platform scheduling (WorkManager / UNUserNotification)
 * lives in `:platform:platform-reminders` (Phase 06), which observes
 * inserts via [observeForOwner] / startup-time [observeUpcoming] and
 * writes back the platform handle via [rebindPlatformHandle].
 */
public interface RemindersRepository {
    /** Active reminders for a single owner (list or item); inactive rows excluded. */
    public fun observeForOwner(owner: ReminderOwner): Flow<List<Reminder>>

    /**
     * Next [limit] active reminders firing after the moment of subscription.
     * "Now" is captured once at subscription — re-emission is DB-driven,
     * not wall-clock-driven (good enough for v1; v2 Calendar tab will need
     * a ticker).
     */
    public fun observeUpcoming(limit: Int): Flow<List<Reminder>>

    public suspend fun schedule(spec: ReminderSpec): Outcome<ReminderId, DataError>

    public suspend fun reschedule(
        id: ReminderId,
        firesAt: Instant,
        recurrence: RecurrenceRule,
    ): Outcome<Unit, DataError>

    /** Soft-deletes the reminder. Platform-handle cleanup is Phase 06's job. */
    public suspend fun cancel(id: ReminderId): Outcome<Unit, DataError>

    /**
     * Records the WorkManager / UNUserNotification request id (or clears it
     * when [handle] is null). Called by the platform layer after a
     * successful schedule/unschedule.
     */
    public suspend fun rebindPlatformHandle(
        id: ReminderId,
        handle: String?,
    ): Outcome<Unit, DataError>
}
