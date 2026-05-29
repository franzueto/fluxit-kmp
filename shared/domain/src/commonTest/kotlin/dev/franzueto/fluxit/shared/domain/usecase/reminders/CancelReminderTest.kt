package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.PlatformHandle
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CancelReminderTest {
    private val owner = ReminderOwner.OfList(sampleListId)

    @Test
    fun disarms_platform_then_tombstones_the_row() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val id = (ScheduleReminder(repo, scheduler, FakeClock(FIXED_NOW))(listReminderSpec()) as Outcome.Ok).value

            assertEquals(Outcome.Ok(Unit), CancelReminder(repo, scheduler)(owner, id))
            assertEquals(listOf(PlatformHandle("handle-0001")), scheduler.cancelled)
            assertTrue(repo.observeForOwner(owner).first().isEmpty())
        }

    @Test
    fun cancelling_an_unknown_reminder_is_an_idempotent_noop() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            assertEquals(Outcome.Ok(Unit), CancelReminder(repo, scheduler)(owner, ReminderId("reminder-99999999")))
            assertTrue(scheduler.cancelled.isEmpty())
        }

    @Test
    fun a_reminder_with_no_platform_handle_skips_the_platform_cancel() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            // Persist directly so platform_handle stays null (never armed).
            val id = (repo.schedule(listReminderSpec()) as Outcome.Ok).value

            assertEquals(Outcome.Ok(Unit), CancelReminder(repo, scheduler)(owner, id))
            assertTrue(scheduler.cancelled.isEmpty())
            assertTrue(repo.observeForOwner(owner).first().isEmpty())
        }

    @Test
    fun platform_failure_aborts_before_the_db_cancel() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val id = (ScheduleReminder(repo, scheduler, FakeClock(FIXED_NOW))(listReminderSpec()) as Outcome.Ok).value
            scheduler.failCancelWith = SchedulerError.SystemBusy

            val err = assertIs<Outcome.Err<DomainError>>(CancelReminder(repo, scheduler)(owner, id))
            assertEquals(DomainError.SchedulerFailure(reason = SchedulerError.SystemBusy), err.error)
            // DB write never happened — the reminder is still active.
            assertEquals(listOf(id), repo.observeForOwner(owner).first().map { it.id })
        }
}
