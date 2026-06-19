plugins {
    id("fluxit.kmp.library")
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.platform.analytics"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // AnalyticsSink/AnalyticsEvent + the AppLogger port (LoggingAnalyticsSink
            // routes events to it) live in :shared:domain. `api` so the port types
            // stay visible to Koin consumers resolving from `analyticsModule`.
            api(project(":shared:domain"))
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // RecordingAppLogger / RecordingAnalyticsSink fakes.
            implementation(project(":shared:domain-testing"))
        }
    }
}
