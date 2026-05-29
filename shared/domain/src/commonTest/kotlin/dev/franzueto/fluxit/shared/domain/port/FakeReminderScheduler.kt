package dev.franzueto.fluxit.shared.domain.port

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Reminder

/**
 * Reusable test fixture for the [ReminderScheduler] port (Phase 04 §11).
 * Records every call so tests can assert the use case armed / disarmed the
 * right schedule, and exposes per-method failure injection so the
 * `SchedulerError` branches (`PermissionDenied`, `SystemBusy`, …) are
 * exercisable.
 *
 * Lands in Slice 13C alongside `ScheduleReminder` / `CancelReminder` /
 * `RehydrateReminders` — the first use cases to consume the port.
 *
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
