plugins {
    id("fluxit.kmp.feature")
    // Compose compiler — the dashboard UI lives in androidMain and uses
    // foundation/material3 composables (mirrors :core:core-designsystem).
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.feature.lists"
    }
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            // The dashboard is wired to the shared MVI store and built from
            // into :shared:data — only domain models surfaced via the store.
            implementation(project(":shared:state"))
            implementation(project(":shared:domain"))
            implementation(project(":core:core-designsystem"))

            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            // `compose-ui` bundle already carries foundation + material3 + navigation-compose.
            implementation(libs.bundles.compose.ui)
            implementation(libs.koin.compose)
        }
        // Pure-logic unit tests for the row subtitle / relative-time formatters
        // (non-Composable) helpers, so they live in androidHostTest and run on
        // the JVM via `testDebugUnitTest`. kotlin("test") supplies the JUnit4
        // runtime + assertions, mirroring :shared:state / :shared:domain.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
