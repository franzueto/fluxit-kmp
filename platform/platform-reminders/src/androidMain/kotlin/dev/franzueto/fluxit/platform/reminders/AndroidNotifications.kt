package dev.franzueto.fluxit.platform.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android notification channel + permission helpers (plan/06 §5). The channel must
 * exist before any notification posts (Android 8+); POST_NOTIFICATIONS is a runtime
 * permission on Android 13+ — the scheduler checks it and refuses to enqueue when
 * denied, leaving the actual "ask" UX to Phase 13.
 */
public object AndroidNotifications {
    /** Reminder notification channel id (plan/06 §5). */
    public const val CHANNEL_ID: String = "fluxit_reminders"

    /** Registers the reminders channel; idempotent (no-op below Android 8). */
    public fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * True when the app may post notifications: always below Android 13, else the
     * POST_NOTIFICATIONS runtime grant. Consumed by the Android reminders flow
     * (Phase 13 wires the request UX).
     */
    public fun isPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
