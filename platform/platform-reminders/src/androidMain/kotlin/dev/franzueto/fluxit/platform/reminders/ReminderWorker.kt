package dev.franzueto.fluxit.platform.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.rule.RecurrenceCalculator
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The WorkManager worker that posts a reminder notification when its schedule
 * fires (plan/06 §5). For `Monthly` recurrence (which WorkManager can't express as
 * a periodic trigger) it re-arms the *next* one-shot after firing — the one-shot
 * chain described in plan/06 §5. WorkManager persistence survives reboot; if a
 * re-arm is ever dropped, `RehydrateReminders` repairs it on next app start.
 */
public class ReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE) ?: "Reminder"
        val body = inputData.getString(KEY_BODY).orEmpty()
        val deepLink = inputData.getString(KEY_DEEP_LINK).orEmpty()
        val notificationSlot = inputData.getInt(KEY_NOTIFICATION_ID, deepLink.hashCode())

        postNotification(title, body, deepLink, notificationSlot)
        rearmMonthlyIfNeeded()
        return Result.success()
    }

    // POST_NOTIFICATIONS is gated by AndroidReminderScheduler before any work is
    // enqueued, and the notify() call is wrapped in runCatching; lint can't see
    // that cross-class guard, so suppress its MissingPermission here.
    @android.annotation.SuppressLint("MissingPermission")
    private fun postNotification(
        title: String,
        body: String,
        deepLink: String,
        notificationSlot: Int,
    ) {
        AndroidNotifications.ensureChannel(applicationContext)
        val tapIntent =
            if (deepLink.isNotEmpty()) {
                android.app.PendingIntent.getActivity(
                    applicationContext,
                    notificationSlot,
                    Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage(applicationContext.packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                null
            }
        val notification =
            NotificationCompat
                .Builder(applicationContext, AndroidNotifications.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .apply { tapIntent?.let(::setContentIntent) }
                .build()
        // NotificationManagerCompat.notify is a no-op when POST_NOTIFICATIONS is
        // denied; the scheduler already gates on that, so this is belt-and-braces.
        runCatching { NotificationManagerCompat.from(applicationContext).notify(notificationSlot, notification) }
    }

    private fun rearmMonthlyIfNeeded() {
        val day = inputData.getInt(KEY_MONTHLY_DAY, -1)
        if (day < 1) return
        val uniqueName = inputData.getString(KEY_UNIQUE_NAME) ?: return
        val firedAt = Instant.fromEpochMilliseconds(inputData.getLong(KEY_FIRES_AT_MS, 0L))
        val next = RecurrenceCalculator.nextFireAfter(RecurrenceRule.Monthly(day), firedAt, TimeZone.currentSystemDefault()) ?: return
        val delayMs = (next.toEpochMilliseconds() - System.currentTimeMillis()).coerceAtLeast(0L)
        val request =
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KEY_TITLE to inputData.getString(KEY_TITLE),
                        KEY_BODY to inputData.getString(KEY_BODY),
                        KEY_DEEP_LINK to inputData.getString(KEY_DEEP_LINK),
                        KEY_NOTIFICATION_ID to inputData.getInt(KEY_NOTIFICATION_ID, 0),
                        KEY_MONTHLY_DAY to day,
                        KEY_UNIQUE_NAME to uniqueName,
                        KEY_FIRES_AT_MS to next.toEpochMilliseconds(),
                    ),
                ).build()
        WorkManager
            .getInstance(applicationContext)
            .enqueueUniqueWork(uniqueName, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    public companion object {
        public const val KEY_TITLE: String = "title"
        public const val KEY_BODY: String = "body"
        public const val KEY_DEEP_LINK: String = "deep_link"
        public const val KEY_NOTIFICATION_ID: String = "notification_id"
        public const val KEY_MONTHLY_DAY: String = "monthly_day"
        public const val KEY_UNIQUE_NAME: String = "unique_name"
        public const val KEY_FIRES_AT_MS: String = "fires_at_ms"
    }
}
