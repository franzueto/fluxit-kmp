import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("fluxit.kmp.library")
}

android {
    namespace = "dev.franzueto.fluxit.shared.state"
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
