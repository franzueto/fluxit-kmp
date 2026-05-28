plugins {
    id("fluxit.kmp.library")
}

android {
    namespace = "dev.franzueto.fluxit.shared.domain"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Pure-Kotlin domain. No SQLDelight, no Android framework, no
            // iOS UIKit/Foundation — enforced by Konsist (ArchitectureTest).
            // kotlinx-datetime: Instant / DayOfWeek used by domain entities.
            // kotlinx-serialization-core: @Serializable annotations on
            // RecurrenceRule only; JSON format runtime stays in :shared:data
            // where the adapter encodes/decodes at the storage boundary
            // (Phase 04 §1 — domain ships annotations, not format).
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.core)
            // kotlinx-coroutines-core: Flow surface on repository interfaces
            // (Phase 03 §5). Domain owns the Flow type so use cases and state
            // can compose reads without depending on :shared:data.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // JSON dep allowed in tests so RecurrenceRule round-trips can be
            // verified without coupling production domain to a format runtime.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
