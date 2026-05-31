package dev.franzueto.fluxit.platform.reminders

import androidx.work.WorkManager
import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Android `remindersModule()` — binds the WorkManager-backed scheduler (plan/06 §5). */
public actual fun remindersModule(): Module =
    module {
        single<ReminderScheduler> {
            AndroidReminderScheduler(
                workManager = WorkManager.getInstance(androidContext()),
                clock = get(),
                notificationsEnabled = { AndroidNotifications.isPermissionGranted(androidContext()) },
            )
        }
    }
