import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("fluxit.kmp.library")
}

android {
    namespace = "dev.franzueto.fluxit.shared.state"
}

// Phase 05 §12 coverage gate: store branch coverage ≥ 90%. Mirrors the
// :shared:domain ≥95% use-case gate (its build.gradle.kts) — Kover instruments
// the JVM/Android unit-test run (which JVM-executes commonTest); the iOS Sim
// target validates the same commonMain sources on Kotlin/Native but isn't
// measured (Kover is JVM-only).
//
// Run the report: `./gradlew :shared:state:koverHtmlReport`
// Enforce the gate: `./gradlew :shared:state:koverVerify`
kover {
    reports {
        // Scope to the store package — §12 is about store logic. The di/
        // composition root is wiring (exercised by KoinGraphTest's checkModules,
        // not branch-meaningful) and the contract data classes are generated.
        filters {
            includes {
                classes("dev.franzueto.fluxit.shared.state.store.*")
            }
        }
        verify {
            rule("Store branch coverage (Phase 05 §12)") {
                bound {
                    minValue = 90
                    coverageUnits = CoverageUnit.BRANCH
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

// Make the §12 gate part of `check` so the pre-commit gate
// (`:shared:state:check`) enforces store branch coverage going forward.
tasks.named("check") {
    dependsOn("koverVerify")
}

// :shared:state is the iOS-facing entry point — ios-app embeds Shared.xcframework
// assembled from this module's three iOS targets. The XCFramework name drives the
// Gradle task names: `assembleSharedXCFramework`, `assembleSharedReleaseXCFramework`,
// `assembleSharedDebugXCFramework`.
kotlin {
    val sharedXcf = XCFramework("Shared")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.withType<Framework>().configureEach {
            baseName = "Shared"
            sharedXcf.add(this)
            // Re-export :shared:domain so the generated Swift framework surfaces
            // domain types (DomainError, use-case result models, the AppLogger
            // port) that appear on store State/Intent/Effect — `export` requires
            // the matching `api(...)` dependency below. (Phase 05 §3 / ADR-014.)
            export(project(":shared:domain"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: domain types appear on the SKIE-exposed
            // store surface, so they must be visible to iOS (paired with `export`
            // above) and to Compose consumers. No dependency on :shared:data —
            // stores compose use cases only (§1).
            api(project(":shared:domain"))
            implementation(libs.bundles.coroutines)
            implementation(libs.kotlinx.datetime)
            // Kermit: stores log through the AppLogger port; the Kermit-backed
            // actual lands in :platform:platform-logging (Phase 06). The runtime
            // dep is here so that actual has a home without a later build edit.
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // runStoreTest harness: TestScope/virtual time + Turbine on state/effects.
            implementation(libs.bundles.testing.shared)
            // Shared domain fakes (FakeListsRepository / FakeRemindersRepository /
            // FakeClock / …) so ListsDashboardStore tests drive real use cases over
            // in-memory repositories instead of bespoke stubs (Phase 05 Slice 4).
            implementation(project(":shared:domain-testing"))
        }
    }
}
