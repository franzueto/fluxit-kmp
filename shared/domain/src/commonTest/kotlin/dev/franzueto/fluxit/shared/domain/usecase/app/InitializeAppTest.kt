package dev.franzueto.fluxit.shared.domain.usecase.app

import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import dev.franzueto.fluxit.shared.domain.repository.FakeRemindersRepository
import dev.franzueto.fluxit.shared.domain.usecase.reminders.RehydrateReminders
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InitializeAppTest {
    private fun seqIds(): IdGenerator {
        var n = 0
        return IdGenerator {
            n++
            "reminder-${n.toString().padStart(8, '0')}"
        }
    }

    private fun remindersRepo() = FakeRemindersRepository(ids = seqIds(), clock = FakeClock(Instant.fromEpochSeconds(1_700_000_000)))

    @Test
    fun emits_started_rehydrated_then_completed_on_success() =
        runTest {
            val rehydrate = RehydrateReminders(remindersRepo(), FakeReminderScheduler())
            val progress = InitializeApp(rehydrate)().toList()
            assertEquals(
                listOf(InitProgress.Started, InitProgress.RemindersRehydrated, InitProgress.Completed),
                progress,
            )
        }

    @Test
    fun terminates_with_failed_when_rehydration_fails() =
        runTest {
            val scheduler = FakeReminderScheduler(failRescheduleWith = SchedulerError.Unknown(cause = null))
            val rehydrate = RehydrateReminders(remindersRepo(), scheduler)
            val progress = InitializeApp(rehydrate)().toList()

            assertEquals(InitProgress.Started, progress.first())
            assertEquals(2, progress.size)
            assertIs<InitProgress.Failed>(progress.last())
        }
}
