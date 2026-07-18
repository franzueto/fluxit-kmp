package dev.franzueto.fluxit.shared.domain.port

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Reminder

/**
 * Not thread-safe; domain tests run on a single coroutine in practice.
 */
public class FakeReminderScheduler(
    public var failScheduleWith: SchedulerError? = null,
    public var failCancelWith: SchedulerError? = null,
    public var failRescheduleWith: SchedulerError? = null,
) : ReminderScheduler {
    /** Reminders passed to [schedule], in call order. */
    public val scheduled: MutableList<Reminder> = mutableListOf()

    /** Handles passed to [cancel], in call order. */
    public val cancelled: MutableList<PlatformHandle> = mutableListOf()

    /** Each batch passed to [rescheduleAll], in call order. */
    public val rescheduledBatches: MutableList<List<Reminder>> = mutableListOf()

    private var handleSeq = 0

    override suspend fun schedule(reminder: Reminder): Outcome<PlatformHandle, SchedulerError> {
        failScheduleWith?.let { return Outcome.Err(it) }
        scheduled += reminder
        handleSeq++
        return Outcome.Ok(PlatformHandle("handle-${handleSeq.toString().padStart(4, '0')}"))
    }

    override suspend fun cancel(handle: PlatformHandle): Outcome<Unit, SchedulerError> {
        failCancelWith?.let { return Outcome.Err(it) }
        cancelled += handle
        return Outcome.Ok(Unit)
    }

    override suspend fun rescheduleAll(active: List<Reminder>): Outcome<Unit, SchedulerError> {
        failRescheduleWith?.let { return Outcome.Err(it) }
        rescheduledBatches += active
        return Outcome.Ok(Unit)
    }
}
