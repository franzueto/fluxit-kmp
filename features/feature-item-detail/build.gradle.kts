plugins {
    id("fluxit.kmp.feature")
    // Compose compiler — the edit-item UI lives in androidMain and uses
    // foundation/material3 composables (mirrors :features:feature-create-list).
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.franzueto.fluxit.feature.itemdetail"
    buildFeatures.compose = true
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            // The edit-item screen is wired to the shared MVI store and built from
            // design-system primitives only (plan/10 §11). It must NOT reach into
            // :shared:data — only domain models surfaced via the store, and the
            // photo capture/permission flows go through the Phase 06 ports the store
            // already orchestrates (no androidx.activity.result.* here).
            implementation(project(":shared:state"))
            implementation(project(":shared:domain"))
            implementation(project(":core:core-designsystem"))

            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            // `compose-ui` bundle already carries foundation + material3 + navigation-compose.
            implementation(libs.bundles.compose.ui)
            implementation(libs.koin.compose)
        }
        // Pure-logic unit tests (label/footer formatters) exercise androidMain
        // non-Composable helpers on the JVM via `testDebugUnitTest`.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
