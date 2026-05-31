package dev.franzueto.fluxit.platform.reminders

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.port.PlatformHandle
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.DayOfWeek
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * `UNUserNotificationCenter`-backed [ReminderScheduler] (plan/06 §5). Each
 * reminder becomes one (or, for `Weekly`, one-per-day) `UNNotificationRequest`
 * with a `UNCalendarNotificationTrigger`. The [PlatformHandle] is the comma-joined
 * request identifier(s) so [cancel] can remove them all.
 *
 * Recurrence → trigger date components:
 *  - `None`    → year/month/day/hour/minute/second, `repeats = false`.
 *  - `Daily`   → hour/minute, `repeats = true`.
 *  - `Weekly`  → hour/minute + weekday, one request per selected day, `repeats = true`.
 *  - `Monthly` → day/hour/minute, `repeats = true` (iOS clamps invalid days, matching `RecurrenceCalculator`).
 *
 * iOS-side behaviour (background delivery, reboot persistence) is exercised by
 * manual device QA per the Phase 06 scope decision; this file is built (not run)
 * by the iOS-Sim gate.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosReminderScheduler(
    private val center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter(),
) : ReminderScheduler {
    override suspend fun schedule(reminder: Reminder): Outcome<PlatformHandle, SchedulerError> {
        if (isDenied()) return Outcome.Err(SchedulerError.PermissionDenied)

        val notification = reminder.toScheduledNotification()
        val content =
            UNMutableNotificationContent().apply {
                setTitle(notification.title)
                setBody(notification.body)
                setSound(UNNotificationSound.defaultSound())
                setUserInfo(mapOf("deepLink" to notification.deepLink))
            }
        val base = reminder.id.value
        val date = NSDate.dateWithTimeIntervalSince1970(reminder.firesAt.toEpochMilliseconds() / 1000.0)

        val identifiers =
            when (val rule = reminder.recurrence) {
                RecurrenceRule.None -> listOf(addRequest(base, content, trigger(date, ONE_SHOT_UNITS, repeats = false)))
                RecurrenceRule.Daily -> listOf(addRequest(base, content, trigger(date, TIME_UNITS, repeats = true)))
                is RecurrenceRule.Monthly ->
                    listOf(addRequest(base, content, trigger(date, MONTHLY_UNITS, repeats = true)))
                is RecurrenceRule.Weekly ->
                    rule.daysOfWeek.map { day ->
                        val comps = NSCalendar.currentCalendar.components(TIME_UNITS, fromDate = date)
                        comps.weekday = day.iosWeekday().toLong()
                        addRequest(
                            "$base#${day.name}",
                            content,
                            UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(comps, repeats = true),
                        )
                    }
            }

        val errors = identifiers.mapNotNull { it.second }
        if (errors.isNotEmpty()) return Outcome.Err(SchedulerError.Unknown(IllegalStateException(errors.first())))
        return Outcome.Ok(PlatformHandle(identifiers.joinToString(SEPARATOR) { it.first }))
    }

    override suspend fun cancel(handle: PlatformHandle): Outcome<Unit, SchedulerError> {
        val ids = handle.raw.split(SEPARATOR).filter { it.isNotEmpty() }
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        return Outcome.Ok(Unit)
    }

    override suspend fun rescheduleAll(active: List<Reminder>): Outcome<Unit, SchedulerError> {
        center.removeAllPendingNotificationRequests()
        active.forEach { reminder ->
            when (val r = schedule(reminder)) {
                is Outcome.Err -> return Outcome.Err(r.error)
                is Outcome.Ok -> Unit
            }
        }
        return Outcome.Ok(Unit)
    }

    private suspend fun isDenied(): Boolean =
        suspendCancellableCoroutine { cont ->
            center.getNotificationSettingsWithCompletionHandler { settings ->
                cont.resume(settings?.authorizationStatus == UNAuthorizationStatusDenied)
            }
        }

    /** Adds the request; returns (identifier, errorMessageOrNull). */
    private suspend fun addRequest(
        identifier: String,
        content: UNMutableNotificationContent,
        trigger: UNCalendarNotificationTrigger,
    ): Pair<String, String?> =
        suspendCancellableCoroutine { cont ->
            val request = UNNotificationRequest.requestWithIdentifier(identifier, content, trigger)
            center.addNotificationRequest(request) { error ->
                cont.resume(identifier to error?.localizedDescription)
            }
        }

    private fun trigger(
        date: NSDate,
        units: ULong,
        repeats: Boolean,
    ): UNCalendarNotificationTrigger {
        val comps: NSDateComponents = NSCalendar.currentCalendar.components(units, fromDate = date)
        return UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(comps, repeats)
    }

    private companion object {
        const val SEPARATOR = ","
        val TIME_UNITS = NSCalendarUnitHour or NSCalendarUnitMinute
        val MONTHLY_UNITS = NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute
        val ONE_SHOT_UNITS =
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
                NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
    }
}

/** kotlinx [DayOfWeek] → iOS weekday (1 = Sunday … 7 = Saturday). */
private fun DayOfWeek.iosWeekday(): Int =
    when (this) {
        DayOfWeek.SUNDAY -> 1
        DayOfWeek.MONDAY -> 2
        DayOfWeek.TUESDAY -> 3
        DayOfWeek.WEDNESDAY -> 4
        DayOfWeek.THURSDAY -> 5
        DayOfWeek.FRIDAY -> 6
        DayOfWeek.SATURDAY -> 7
        else -> 1
    }
