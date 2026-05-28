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
            // :core:core-utils owns the platform-neutral `newId()` expect/
            // actual (ADR-006a — placed in core-utils so both :shared:domain
            // and :shared:data can depend on it without a cycle) and the
            // `IdGenerator` fun-interface seam. Phase 04 §5 re-exports
            // IdGenerator as a domain port via typealias so use-case call
            // sites import from `dev.franzueto.fluxit.shared.domain.port`.
            implementation(project(":core:core-utils"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // JSON dep allowed in tests so RecurrenceRule round-trips can be
            // verified without coupling production domain to a format runtime.
            implementation(libs.kotlinx.serialization.json)
            // runTest for §11 repository-fake tests (Slice 9+) — fakes return
            // Flow / suspend, tests need the structured-concurrency test
            // runner. Same dep :shared:data:commonTest uses for its
            // SqlListsRepositorySmokeTest et al.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
