package dev.franzueto.fluxit.platform.reminders

import dev.franzueto.fluxit.shared.domain.port.ReminderScheduler
import org.koin.core.module.Module
import org.koin.dsl.module

public actual fun remindersModule(): Module =
    module {
        single<ReminderScheduler> { IosReminderScheduler() }
    }
