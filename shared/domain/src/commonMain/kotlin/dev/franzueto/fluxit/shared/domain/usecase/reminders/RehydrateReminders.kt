package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import kotlinx.coroutines.flow.first

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
