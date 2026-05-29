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
 * Schedule a reminder (Phase 04 §7): validate, persist the row, arm the
 * OS-level schedule, then write the platform handle back.
 *
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
 *
 * **Spec/reality reconciliation:** the §7 punch list said a `PermissionDenied`
 * failure leaves the row at `is_active = 0, platform_handle = NULL` for an
 * in-place retry. The shipped `RemindersRepository` has no "deactivate but
 * keep" primitive distinct from `cancel` (which tombstones + flips active),
 * so the failed row is tombstoned and "retry" means re-invoking this use case
 * (a fresh row) — which is exactly the permission-prompt-then-retry flow the
 * UI runs anyway.
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository/port, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
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
