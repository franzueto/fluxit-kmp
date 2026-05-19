plugins {
    id("fluxit.kmp.library")
    id("fluxit.designsystem.tokens")
    // Compose compiler is required for inline Composables (Row, Column, Box).
    // FluxItTheme + FluxItScaffold only used non-inline Composables and got
    // away without it; §5 primitives use foundation layouts heavily.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.franzueto.fluxit.core.designsystem"
    buildFeatures.compose = true
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            // Generated FluxItColors / FluxItTypography / FluxItShapes /
            // FluxItElevation / FluxItSpacing reference Compose types
            // (Color, TextStyle, Dp, RoundedCornerShape). These deps make
            // the generated code compile on the Android side. iOS side has
            // no Kotlin consumers of these types — SwiftEmitter writes
            // FluxItTokens.swift to ios-app/Generated/ instead.
            // Kotlin 2.1 deprecated `platform(Provider<...>)` on the KMP
            // KotlinDependencyHandler; route through project.dependencies.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.foundation)
            // FluxItTheme builds a Material3 darkColorScheme + Typography
            // from the generated FluxItColors / FluxItTypography (ADR-005b).
            implementation(libs.androidx.compose.material3)
        }
    }
}
