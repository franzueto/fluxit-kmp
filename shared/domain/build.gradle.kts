import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    id("fluxit.kmp.library")
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.shared.domain"
    }
}

// tolerate 5% for trivial guards). Kover instruments the JVM/Android tests —
// (which JVM-executes commonTest) exercises every branch. The iOS Sim test
// target validates the same sources on Kotlin/Native but is not measured
// (Kover is JVM-only); equivalence is given by the shared commonMain sources.
//
// Run the report: `./gradlew :shared:domain:koverHtmlReport`
// Enforce the gate: `./gradlew :shared:domain:koverVerify`
kover {
    reports {
        // use-case logic, not the whole module (entities/value objects are data
        // classes whose compiler-generated branches aren't the target).
        filters {
            includes {
                classes("dev.franzueto.fluxit.shared.domain.usecase.*")
            }
        }
        verify {
            rule("Use-case branch coverage") {
                bound {
                    minValue = 95
                    coverageUnits = CoverageUnit.BRANCH
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

// (`:shared:domain:check`) enforces use-case branch coverage going forward,
// not just an opt-in `koverVerify` invocation.
tasks.named("check") {
    dependsOn("koverVerify")
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
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.core)
            // kotlinx-coroutines-core: Flow surface on repository interfaces
            // can compose reads without depending on :shared:data.
            implementation(libs.kotlinx.coroutines.core)
            // :core:core-utils owns the platform-neutral `newId()` expect/
            // actual (ADR-006a — placed in core-utils so both :shared:domain
            // and :shared:data can depend on it without a cycle) and the
            // IdGenerator as a domain port via typealias so use-case call
            // sites import from `dev.franzueto.fluxit.shared.domain.port`.
            implementation(project(":core:core-utils"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Repository / port fakes (FakeListsRepository, FakeClock, …) now live
            // in :shared:domain-testing commonMain so :shared:state tests can reuse
            // back in via this dependency — package paths are unchanged.
            implementation(project(":shared:domain-testing"))
            // JSON dep allowed in tests so RecurrenceRule round-trips can be
            // verified without coupling production domain to a format runtime.
            implementation(libs.kotlinx.serialization.json)
            // Flow / suspend, tests need the structured-concurrency test
            // runner. Same dep :shared:data:commonTest uses for its
            // SqlListsRepositorySmokeTest et al.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
