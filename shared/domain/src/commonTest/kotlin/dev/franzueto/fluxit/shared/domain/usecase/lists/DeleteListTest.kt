package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.DeletedListSummary
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import dev.franzueto.fluxit.shared.domain.repository.FakeRemindersRepository
import dev.franzueto.fluxit.shared.domain.usecase.reminders.CancelReminder
import dev.franzueto.fluxit.shared.domain.usecase.reminders.ScheduleReminder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class DeleteListTest {
    private val now = Instant.fromEpochSeconds(1_700_000_000)

    private fun reminderIds(): IdGenerator {
        var n = 0
        return IdGenerator {
            n++
            "reminder-${n.toString().padStart(8, '0')}"
        }
    }

    private fun newRemindersRepo() = FakeRemindersRepository(ids = reminderIds(), clock = FakeClock(now))

    private fun listReminderSpec(listId: ListId) = ReminderSpec(owner = ReminderOwner.OfList(listId), firesAt = now.plus(1.hours))

    @Test
    fun soft_deletes_the_list_cancels_its_reminders_and_returns_a_summary() =
        runTest {
            val lists = newRepo()
            val reminders = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val listId = (lists.create(draft("Groceries")) as Outcome.Ok).value
            // Arm two reminders so the platform-disarm path is exercised.
            val r1 = (ScheduleReminder(reminders, scheduler, FakeClock(now))(listReminderSpec(listId)) as Outcome.Ok).value
            val r2 = (ScheduleReminder(reminders, scheduler, FakeClock(now))(listReminderSpec(listId)) as Outcome.Ok).value

            val deleteList = DeleteList(lists, reminders, CancelReminder(reminders, scheduler))
            val summary = assertIs<Outcome.Ok<DeletedListSummary>>(deleteList(listId)).value

            assertEquals(listId, summary.id)
            assertEquals("Groceries", summary.name)
            assertEquals(setOf(r1, r2), summary.cancelledReminderIds.toSet())
            assertNull(lists.observe(listId).first())
            assertTrue(reminders.observeForOwner(ReminderOwner.OfList(listId)).first().isEmpty())
            assertEquals(2, scheduler.cancelled.size)
        }

    @Test
    fun a_list_with_no_reminders_deletes_cleanly() =
        runTest {
            val lists = newRepo()
            val reminders = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val listId = (lists.create(draft("Empty")) as Outcome.Ok).value

            val deleteList = DeleteList(lists, reminders, CancelReminder(reminders, scheduler))
            val summary = (deleteList(listId) as Outcome.Ok).value

            assertTrue(summary.cancelledReminderIds.isEmpty())
            assertNull(lists.observe(listId).first())
            assertTrue(scheduler.cancelled.isEmpty())
        }

    @Test
    fun missing_list_is_domain_not_found() =
        runTest {
            val lists = newRepo()
            val reminders = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val bogus = ListId("list-99999999")

            val deleteList = DeleteList(lists, reminders, CancelReminder(reminders, scheduler))
            val err = assertIs<Outcome.Err<DomainError>>(deleteList(bogus))
            assertEquals(DomainError.NotFound(entity = "List", id = bogus.value), err.error)
        }

    @Test
    fun scheduler_failure_aborts_before_the_list_is_deleted() =
        runTest {
            val lists = newRepo()
            val reminders = newRemindersRepo()
            val scheduler = FakeReminderScheduler()
            val listId = (lists.create(draft("Groceries")) as Outcome.Ok).value
            ScheduleReminder(reminders, scheduler, FakeClock(now))(listReminderSpec(listId))
            scheduler.failCancelWith = SchedulerError.PermissionDenied

            val deleteList = DeleteList(lists, reminders, CancelReminder(reminders, scheduler))
            val err = assertIs<Outcome.Err<DomainError>>(deleteList(listId))
            assertEquals(DomainError.SchedulerFailure(reason = SchedulerError.PermissionDenied), err.error)
            // The list survived — the operation is retryable.
            assertEquals(listId, lists.observe(listId).first()?.id)
        }
}
