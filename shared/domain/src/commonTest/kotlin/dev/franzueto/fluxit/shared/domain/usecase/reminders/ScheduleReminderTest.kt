package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class ScheduleReminderTest {
    private val owner = ReminderOwner.OfList(sampleListId)

    @Test
    fun persists_arms_platform_and_rebinds_handle() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val result = ScheduleReminder(repo, scheduler, FakeClock(FIXED_NOW))(listReminderSpec())

            val id = assertIs<Outcome.Ok<ReminderId>>(result).value
            assertEquals(1, scheduler.scheduled.size)
            val stored = repo.observeForOwner(owner).first().single()
            assertEquals(id, stored.id)
            assertEquals("handle-0001", stored.platformHandle)
            assertTrue(stored.isActive)
        }

    @Test
    fun firesAt_in_the_past_is_rejected_before_any_side_effect() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val spec = listReminderSpec(firesAt = FIXED_NOW.minus(1.hours))
            val err = assertIs<Outcome.Err<DomainError>>(ScheduleReminder(repo, scheduler, FakeClock(FIXED_NOW))(spec))

            assertEquals(DomainError.Validation(field = "firesAt", rule = ValidationError.NotInFuture), err.error)
            assertTrue(scheduler.scheduled.isEmpty())
            assertTrue(repo.observeForOwner(owner).first().isEmpty())
        }

    @Test
    fun platform_failure_surfaces_scheduler_failure_and_tombstones_the_row() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler(failScheduleWith = SchedulerError.PermissionDenied)
            val err = assertIs<Outcome.Err<DomainError>>(ScheduleReminder(repo, scheduler, FakeClock(FIXED_NOW))(listReminderSpec()))

            assertEquals(DomainError.SchedulerFailure(reason = SchedulerError.PermissionDenied), err.error)
            // The phantom row was cancelled — no active reminder remains.
            assertTrue(repo.observeForOwner(owner).first().isEmpty())
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val repo = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val message =
                ScheduleReminder(repo, scheduler, FakeClock(FIXED_NOW))(listReminderSpec())
                    .fold(onOk = { "scheduled" }, onErr = { "failed: $it" })
            assertEquals("scheduled", message)
        }
}
