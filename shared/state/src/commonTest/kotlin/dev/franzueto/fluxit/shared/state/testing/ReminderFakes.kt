package dev.franzueto.fluxit.shared.state.testing

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.port.PlatformHandle
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant

// Minimal local fakes for the two seams RootStore reaches through InitializeApp
// → RehydrateReminders: RemindersRepository.observeUpcoming and
// ReminderScheduler.rescheduleAll. The remaining interface methods are not
// exercised by RootStore, so they fail loudly if a future test wanders into them.
//
// These are intentionally local to :shared:state tests. The richer reusable
// repository fakes already live in :shared:domain commonTest; sharing them
// cross-module needs a dedicated test-fixtures module, introduced in Slice 4
// when ListsDashboardStore needs the full Lists/Items fake surface.

internal class StubRemindersRepository(
    private val upcoming: List<Reminder> = emptyList(),
) : RemindersRepository {
    override fun observeUpcoming(limit: Int): Flow<List<Reminder>> = flowOf(upcoming)

    override fun observeForOwner(owner: ReminderOwner): Flow<List<Reminder>> = error("not exercised by RootStore tests")

    override suspend fun schedule(spec: ReminderSpec): Outcome<ReminderId, DataError> = error("not exercised by RootStore tests")

    override suspend fun reschedule(
        id: ReminderId,
        firesAt: Instant,
        recurrence: RecurrenceRule,
    ): Outcome<Unit, DataError> = error("not exercised by RootStore tests")

    override suspend fun cancel(id: ReminderId): Outcome<Unit, DataError> = error("not exercised by RootStore tests")

    override suspend fun rebindPlatformHandle(
        id: ReminderId,
        handle: String?,
    ): Outcome<Unit, DataError> = error("not exercised by RootStore tests")
}

internal class StubReminderScheduler(
    private val rescheduleAllResult: Outcome<Unit, SchedulerError> = Outcome.Ok(Unit),
) : ReminderScheduler {
    override suspend fun rescheduleAll(active: List<Reminder>): Outcome<Unit, SchedulerError> = rescheduleAllResult

    override suspend fun schedule(reminder: Reminder): Outcome<PlatformHandle, SchedulerError> = error("not exercised by RootStore tests")

    override suspend fun cancel(handle: PlatformHandle): Outcome<Unit, SchedulerError> = error("not exercised by RootStore tests")
}
