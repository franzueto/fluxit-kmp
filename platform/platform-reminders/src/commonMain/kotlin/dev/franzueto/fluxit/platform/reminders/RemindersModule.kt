package dev.franzueto.fluxit.platform.reminders

import org.koin.core.module.Module

/**
 * Koin module for `:platform:platform-reminders` (plan/06 §5, §8). Binds the
 * production [ReminderScheduler][dev.franzueto.fluxit.shared.domain.port.ReminderScheduler]
 * actual — `AndroidReminderScheduler` (WorkManager + NotificationCompat) on
 * Android, `IosReminderScheduler` (UNUserNotificationCenter) on iOS.
 *
 * `expect`/`actual` rather than a single common `module { }` because the Android
 * binding needs an `androidContext()` (Koin-Android) and the iOS binding needs a
 * `UNUserNotificationCenter` — neither is expressible in commonMain. Replaces the
 * interim `NoOpReminderScheduler` in `:shared:state`'s `InterimPlatformModule`
 * (Slice 6).
 */
public expect fun remindersModule(): Module
