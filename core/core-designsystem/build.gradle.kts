plugins {
    id("fluxit.kmp.library")
    id("fluxit.designsystem.tokens")
}

android {
    namespace = "dev.franzueto.fluxit.core.designsystem"
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
