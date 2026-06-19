package dev.franzueto.fluxit.platform.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AndroidReminderSchedulerTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = Clock { now }
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun scheduler(notificationsEnabled: Boolean = true) =
        AndroidReminderScheduler(workManager, clock, notificationsEnabled = { notificationsEnabled })

    private fun reminder(
        id: String,
        recurrence: RecurrenceRule,
    ) = Reminder(
        id = ReminderId(id),
        owner = ReminderOwner.OfList(ListId("list-1")),
        firesAt = now.plusDays(1),
        recurrence = recurrence,
        platformHandle = null,
        isActive = true,
        createdAt = now,
        updatedAt = now,
    )

    private fun Instant.plusDays(d: Int) = Instant.fromEpochMilliseconds(toEpochMilliseconds() + d * 86_400_000L)

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun one_shot_enqueues_one_unique_work_and_returns_its_name_as_handle() =
        runBlocking {
            val result = scheduler().schedule(reminder("r1", RecurrenceRule.None))
            assertTrue(result is Outcome.Ok)
            assertEquals("r1", (result as Outcome.Ok).value.raw)
            assertEquals(1, infos("r1").size)
        }

    @Test
    fun weekly_enqueues_one_request_per_selected_day() =
        runBlocking {
            val rule = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
            val result = scheduler().schedule(reminder("r2", rule))
            assertTrue(result is Outcome.Ok)
            val handle = (result as Outcome.Ok).value.raw
            assertEquals(3, handle.split(",").size)
            assertEquals(1, infos("r2#MONDAY").size)
            assertEquals(1, infos("r2#WEDNESDAY").size)
            assertEquals(1, infos("r2#FRIDAY").size)
        }

    @Test
    fun permission_denied_returns_error_and_enqueues_nothing() =
        runBlocking {
            val result = scheduler(notificationsEnabled = false).schedule(reminder("r3", RecurrenceRule.None))
            assertEquals(Outcome.Err(SchedulerError.PermissionDenied), result)
            assertTrue(infos("r3").isEmpty())
        }

    @Test
    fun cancel_removes_every_request_addressed_by_the_handle() =
        runBlocking {
            val s = scheduler()
            val handle = (s.schedule(reminder("r4", RecurrenceRule.Daily)) as Outcome.Ok).value
            assertEquals(1, infos("r4").size)
            s.cancel(handle)
            val state = infos("r4").firstOrNull()?.state
            assertTrue(state == null || state == WorkInfo.State.CANCELLED)
        }
}
