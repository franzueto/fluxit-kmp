package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import kotlinx.coroutines.flow.first

/**
 * Repopulate the OS-level schedule on app start / boot-completed (Android)
 * and cold-start (iOS) so reminders survive a reboot or process death
 * (Phase 04 §7). Reads the active reminders, then hands them to
 * [ReminderScheduler.rescheduleAll] in one batch.
 *
 * **Spec/reality reconciliation:** the punch list said to read via
 * `RemindersRepository.selectActive`, but the shipped contract exposes no
 * such method — only [RemindersRepository.observeUpcoming] (active rows
 * firing after the subscription "now"). That's the right set to re-arm:
 * past-due rows are moot for rehydration (they've already fired or are
 * stale), so this reads `observeUpcoming(Int.MAX_VALUE).first()` for an
 * unbounded one-shot snapshot. A `SchedulerError` surfaces as
 * [DomainError.SchedulerFailure].
 *
 *
 * **Concurrency (§9):** caller dispatcher — any; this use case does not block.
 * It suspends only on the injected repository/port, which owns its dispatcher;
 * the domain stays dispatcher-agnostic (no `withContext`/`Dispatchers.*`).
 */
public class RehydrateReminders(
    private val reminders: RemindersRepository,
    private val scheduler: ReminderScheduler,
) {
    public suspend operator fun invoke(): Outcome<Unit, DomainError> {
        val active = reminders.observeUpcoming(limit = Int.MAX_VALUE).first()
        return when (val result = scheduler.rescheduleAll(active)) {
            is Outcome.Err -> Outcome.Err(DomainError.SchedulerFailure(reason = result.error))
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }
}
