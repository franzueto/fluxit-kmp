plugins {
    id("fluxit.kmp.library")
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.platform.logging"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The AppLogger port lives in :shared:domain; this module ships its
            // production actual. `api` so the port type stays visible to Koin
            // consumers resolving `AppLogger` from `loggingModule`.
            api(project(":shared:domain"))
            // Kermit is the logging backbone (plan/06 §2): KermitAppLogger adapts
            // the domain port onto a Kermit Logger; the platform LogWriters
            // (Logcat / os_log) come from expect/actual `platformLogWriters()`.
            implementation(libs.kermit)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
