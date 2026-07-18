package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.error.map
import dev.franzueto.fluxit.shared.domain.error.mapError
import dev.franzueto.fluxit.shared.domain.error.toDomain
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository

/**
 * 1. Edge validation — `firesAt` must be strictly in the future
 *    ([Clock.now]); a past/now value is [DomainError.Validation] with
 *    [ValidationError.NotInFuture], produced directly.
 * 2. `RemindersRepository.schedule` persists the row (`is_active = true`,
 *    `platform_handle = null`) and mints the [ReminderId].
 * 3. [ReminderScheduler.schedule] arms the OS schedule. On success the
 *    returned [dev.franzueto.fluxit.shared.domain.port.PlatformHandle] is
 *    written back via `rebindPlatformHandle`. On failure the just-created
 *    row is cancelled (best-effort cleanup) and the typed
 *    [dev.franzueto.fluxit.shared.domain.port.SchedulerError] surfaces as
 *    [DomainError.SchedulerFailure] so the UI can prompt for permission and
 *    retry.
 */
public class ScheduleReminder(
    private val reminders: RemindersRepository,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {
    public suspend operator fun invoke(spec: ReminderSpec): Outcome<ReminderId, DomainError> {
        val now = clock.now()
        if (spec.firesAt <= now) {
            return Outcome.Err(DomainError.Validation(field = "firesAt", rule = ValidationError.NotInFuture))
        }

        val id =
            when (val persisted = reminders.schedule(spec).mapError { it.toDomain(entity = "Reminder") }) {
                is Outcome.Err -> return persisted
                is Outcome.Ok -> persisted.value
            }

        val reminder =
            Reminder(
                id = id,
                owner = spec.owner,
                firesAt = spec.firesAt,
                recurrence = spec.recurrence,
                platformHandle = null,
                isActive = true,
                createdAt = now,
                updatedAt = now,
            )

        return when (val armed = scheduler.schedule(reminder)) {
            is Outcome.Err -> {
                // Best-effort cleanup: the OS schedule never armed, so the
                // persisted row would be a phantom. Tombstone it, then surface
                // the typed scheduler failure for the UI's retry flow.
                reminders.cancel(id)
                Outcome.Err(DomainError.SchedulerFailure(reason = armed.error))
            }
            is Outcome.Ok ->
                reminders
                    .rebindPlatformHandle(id, armed.value.raw)
                    .mapError { it.toDomain(entity = "Reminder") }
                    .map { id }
        }
    }
}
