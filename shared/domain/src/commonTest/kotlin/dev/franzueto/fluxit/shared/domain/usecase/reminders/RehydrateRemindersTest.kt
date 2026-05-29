package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours

class RehydrateRemindersTest {
    @Test
    fun re_arms_every_active_upcoming_reminder_in_one_batch() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val a = (repo.schedule(listReminderSpec(firesAt = FIXED_NOW.plus(1.hours))) as Outcome.Ok).value
            val b = (repo.schedule(listReminderSpec(firesAt = FIXED_NOW.plus(2.hours))) as Outcome.Ok).value

            assertEquals(Outcome.Ok(Unit), RehydrateReminders(repo, scheduler)())
            assertEquals(1, scheduler.rescheduledBatches.size)
            assertEquals(listOf(a, b), scheduler.rescheduledBatches.single().map { it.id })
        }

    @Test
    fun empty_store_re_arms_an_empty_batch() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            assertEquals(Outcome.Ok(Unit), RehydrateReminders(repo, scheduler)())
            assertEquals(listOf(emptyList()), scheduler.rescheduledBatches)
        }

    @Test
    fun scheduler_failure_surfaces_as_scheduler_failure() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler(failRescheduleWith = SchedulerError.Unknown(cause = null))
            repo.schedule(listReminderSpec())

            val err = assertIs<Outcome.Err<DomainError>>(RehydrateReminders(repo, scheduler)())
            assertEquals(DomainError.SchedulerFailure(reason = SchedulerError.Unknown(cause = null)), err.error)
        }
}
