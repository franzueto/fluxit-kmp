package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.port.PlatformHandle
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import kotlinx.coroutines.flow.first

/**
 * Cancel a reminder (Phase 04 §7): disarm the OS schedule first, then
 * tombstone the row. **Idempotent** — cancelling a reminder that's already
 * gone (or was never scheduled) is a no-op `Ok`.
 *
 * Takes `(owner, id)` rather than a bare [ReminderId] because the shipped
 * [RemindersRepository] exposes no `observe(id)` lookup — only
 * [RemindersRepository.observeForOwner]. The UI already holds the owner from
 * the [observeForOwner][RemindersRepository.observeForOwner] feed it rendered.
 *
 * If the reminder carries a
 * [platformHandle][Reminder.platformHandle], [ReminderScheduler.cancel] runs
 * first; a [dev.franzueto.fluxit.shared.domain.port.SchedulerError] aborts
 * before the DB write (so a retry can re-attempt the platform cancel) and
 * surfaces as [DomainError.SchedulerFailure]. A reminder with no handle
 * (never armed) skips straight to the DB cancel.
 */
public class CancelReminder(
    private val reminders: RemindersRepository,
    private val scheduler: ReminderScheduler,
) {
    public suspend operator fun invoke(
        owner: ReminderOwner,
        id: ReminderId,
    ): Outcome<Unit, DomainError> {
        val reminder: Reminder =
            reminders
                .observeForOwner(owner)
                .first()
                .firstOrNull { it.id == id }
                ?: return Outcome.Ok(Unit) // idempotent: already cancelled / never existed

        reminder.platformHandle?.let { handle ->
            when (val disarmed = scheduler.cancel(PlatformHandle(handle))) {
                is Outcome.Err -> return Outcome.Err(DomainError.SchedulerFailure(reason = disarmed.error))
                is Outcome.Ok -> Unit
            }
        }

        return reminders.cancel(id).mapError { it.toDomain(entity = "Reminder") }
    }
}
