plugins {
    id("fluxit.kmp.feature")
    // Compose compiler — the edit-item UI lives in androidMain and uses
    // foundation/material3 composables (mirrors :features:feature-create-list).
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.feature.itemdetail"
    }
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            // The edit-item screen is wired to the shared MVI store and built from
            // :shared:data — only domain models surfaced via the store, and the
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
