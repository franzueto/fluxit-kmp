package dev.franzueto.fluxit.platform.reminders

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.PlatformHandle
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import dev.franzueto.fluxit.shared.domain.rule.RecurrenceCalculator
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit

/**
 * WorkManager-backed [ReminderScheduler] (plan/06 §5; ADR-009a — best-effort over
 * exact `AlarmManager`). Each reminder maps to one or more WorkManager requests
 * keyed by a unique work name derived from the reminder id; the [PlatformHandle]
 * is the comma-joined list of those names so [cancel] can address every one.
 *
 *  - `None`    → one [OneTimeWorkRequest][androidx.work.OneTimeWorkRequest] at `firesAt`.
 *  - `Daily`   → one 24h [PeriodicWorkRequest][androidx.work.PeriodicWorkRequest].
 *  - `Weekly`  → one 7-day periodic request **per selected day-of-week**.
 *  - `Monthly` → one one-shot; [ReminderWorker] re-arms the next after firing.
 *
 * POST_NOTIFICATIONS (Android 13+) is checked before enqueuing; denied →
 * `SchedulerError.PermissionDenied` with nothing queued (plan/06 §5).
 */
public class AndroidReminderScheduler(
    private val workManager: WorkManager,
    private val clock: Clock,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val notificationsEnabled: () -> Boolean,
) : ReminderScheduler {
    override suspend fun schedule(reminder: Reminder): Outcome<PlatformHandle, SchedulerError> {
        if (!notificationsEnabled()) return Outcome.Err(SchedulerError.PermissionDenied)
        return runCatching {
            val notification = reminder.toScheduledNotification()
            val base = reminder.id.value
            val names =
                when (val rule = reminder.recurrence) {
                    RecurrenceRule.None -> {
                        enqueueOneShot(base, notification, firstFire(reminder, rule), monthlyDay = null)
                        listOf(base)
                    }
                    RecurrenceRule.Daily -> {
                        enqueuePeriodic(base, notification, firstFire(reminder, rule), intervalDays = 1)
                        listOf(base)
                    }
                    is RecurrenceRule.Monthly -> {
                        enqueueOneShot(base, notification, firstFire(reminder, rule), monthlyDay = rule.dayOfMonth)
                        listOf(base)
                    }
                    is RecurrenceRule.Weekly -> {
                        rule.daysOfWeek.map { day ->
                            val name = "$base#${day.name}"
                            val singleDay = RecurrenceRule.Weekly(setOf(day))
                            enqueuePeriodic(name, notification, firstFire(reminder, singleDay), intervalDays = 7)
                            name
                        }
                    }
                }
            PlatformHandle(names.joinToString(SEPARATOR))
        }.fold(
            onSuccess = { Outcome.Ok(it) },
            onFailure = { Outcome.Err(SchedulerError.Unknown(it)) },
        )
    }

    override suspend fun cancel(handle: PlatformHandle): Outcome<Unit, SchedulerError> =
        runCatching {
            handle.raw
                .split(SEPARATOR)
                .filter { it.isNotEmpty() }
                .forEach { workManager.cancelUniqueWork(it) }
        }.fold(
            onSuccess = { Outcome.Ok(Unit) },
            onFailure = { Outcome.Err(SchedulerError.Unknown(it)) },
        )

    override suspend fun rescheduleAll(active: List<Reminder>): Outcome<Unit, SchedulerError> {
        // Unique-work REPLACE/UPDATE means re-scheduling overwrites any stale request,
        // so a plain re-schedule of every active reminder is the rehydration (plan/06 §5).
        active.forEach { reminder ->
            when (val r = schedule(reminder)) {
                is Outcome.Err -> return Outcome.Err(r.error)
                is Outcome.Ok -> Unit
            }
        }
        return Outcome.Ok(Unit)
    }

    private fun firstFire(
        reminder: Reminder,
        rule: RecurrenceRule,
    ): Instant {
        val now = clock.now()
        if (rule == RecurrenceRule.None) return reminder.firesAt
        var t = reminder.firesAt
        if (rule is RecurrenceRule.Weekly && t.toLocalDateTime(timeZone).dayOfWeek !in rule.daysOfWeek) {
            t = RecurrenceCalculator.nextFireAfter(rule, t, timeZone) ?: t
        }
        while (t <= now) {
            t = RecurrenceCalculator.nextFireAfter(rule, t, timeZone) ?: return now
        }
        return t
    }

    private fun delayMsTo(target: Instant): Long = (target.toEpochMilliseconds() - clock.now().toEpochMilliseconds()).coerceAtLeast(0L)

    private fun enqueueOneShot(
        uniqueName: String,
        notification: ScheduledNotification,
        firesAt: Instant,
        monthlyDay: Int?,
    ) {
        val request =
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delayMsTo(firesAt), TimeUnit.MILLISECONDS)
                .setInputData(inputData(uniqueName, notification, firesAt, monthlyDay))
                .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    private fun enqueuePeriodic(
        uniqueName: String,
        notification: ScheduledNotification,
        firstFire: Instant,
        intervalDays: Long,
    ) {
        val request =
            PeriodicWorkRequestBuilder<ReminderWorker>(intervalDays, TimeUnit.DAYS)
                .setInitialDelay(delayMsTo(firstFire), TimeUnit.MILLISECONDS)
                .setInputData(inputData(uniqueName, notification, firstFire, monthlyDay = null))
                .build()
        workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun inputData(
        uniqueName: String,
        notification: ScheduledNotification,
        firesAt: Instant,
        monthlyDay: Int?,
    ) = workDataOf(
        ReminderWorker.KEY_TITLE to notification.title,
        ReminderWorker.KEY_BODY to notification.body,
        ReminderWorker.KEY_DEEP_LINK to notification.deepLink,
        ReminderWorker.KEY_NOTIFICATION_ID to uniqueName.hashCode(),
        ReminderWorker.KEY_MONTHLY_DAY to (monthlyDay ?: -1),
        ReminderWorker.KEY_UNIQUE_NAME to uniqueName,
        ReminderWorker.KEY_FIRES_AT_MS to firesAt.toEpochMilliseconds(),
    )

    private companion object {
        const val SEPARATOR = ","
    }
}
