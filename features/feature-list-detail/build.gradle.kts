plugins {
    id("fluxit.kmp.feature")
    // Compose compiler — the list-detail UI lives in androidMain and uses
    // foundation/material3 composables (mirrors :features:feature-lists).
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "dev.franzueto.fluxit.feature.listdetail"
    }
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            // The list-detail screen is wired to the shared MVI store and built
            // reach into :shared:data — only domain models surfaced via the store.
            implementation(project(":shared:state"))
            implementation(project(":shared:domain"))
            implementation(project(":core:core-designsystem"))

            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            // `compose-ui` bundle already carries foundation + material3 + navigation-compose.
            implementation(libs.bundles.compose.ui)
            implementation(libs.koin.compose)
        }
        // Pure-logic unit tests for the completion-fraction / row formatters
        // they live in androidHostTest and run on the JVM via `testDebugUnitTest`.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
