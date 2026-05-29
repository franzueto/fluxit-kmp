import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    id("fluxit.kmp.library")
}

android {
    namespace = "dev.franzueto.fluxit.shared.domain"
}

// Phase 04 §13 hand-off gate: use-case branch coverage ≥ 95% (target 100%,
// tolerate 5% for trivial guards). Kover instruments the JVM/Android tests —
// the §7 use cases are pure common code, so the android debug unit-test run
// (which JVM-executes commonTest) exercises every branch. The iOS Sim test
// target validates the same sources on Kotlin/Native but is not measured
// (Kover is JVM-only); equivalence is given by the shared commonMain sources.
//
// Run the report: `./gradlew :shared:domain:koverHtmlReport`
// Enforce the gate: `./gradlew :shared:domain:koverVerify`
kover {
    reports {
        // Scope reports + the gate to the usecase/ package — §13 is about
        // use-case logic, not the whole module (entities/value objects are data
        // classes whose compiler-generated branches aren't the target).
        filters {
            includes {
                classes("dev.franzueto.fluxit.shared.domain.usecase.*")
            }
        }
        verify {
            rule("Use-case branch coverage (Phase 04 §13)") {
                bound {
                    minValue = 95
                    coverageUnits = CoverageUnit.BRANCH
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

// Make the §13 gate part of `check` so the pre-commit gate
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
