plugins {
    id("fluxit.kmp.library")
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.platform.reminders"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ReminderScheduler port + Reminder/RecurrenceRule model + RecurrenceCalculator.
            api(project(":shared:domain"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.bundles.coroutines)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            // WorkManager (best-effort scheduling, ADR-009a) + NotificationCompat.
            implementation(libs.androidx.work.runtime)
            implementation(libs.androidx.core.ktx)
            // androidContext() in the android `remindersModule()` actual.
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // FakeReminderScheduler + FakeClock for the common mapper/contract tests.
            implementation(project(":shared:domain-testing"))
        }
        androidHostTest.dependencies {
            // JVM-host Android tests: Robolectric drives a real WorkManager test
            // harness so the scheduler's enqueue/cancel behaviour is asserted.
            implementation(libs.robolectric)
            implementation(libs.androidx.work.testing)
            implementation(libs.androidx.test.core)
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
