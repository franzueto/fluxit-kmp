plugins {
    `kotlin-dsl`
}

// Precompiled script plugins live under src/main/kotlin and are exposed as plugin
// IDs derived from their file name (e.g. `fluxit.kmp.library.gradle.kts` → plugin
// id `fluxit.kmp.library`). To resolve the Gradle plugin classes they reference
// (KotlinMultiplatformExtension, AGP, SKIE, etc.), we put those plugins on this
// module's classpath as regular dependencies.

dependencies {
    implementation(libs.build.kotlin.gradle.plugin)
    implementation(libs.build.android.gradle.plugin)
    implementation(libs.build.skie.gradle.plugin)
    implementation(libs.build.sqldelight.gradle.plugin)
    implementation(libs.build.detekt.gradle.plugin)
    implementation(libs.build.spotless.gradle.plugin)
    implementation(libs.build.ktlint.gradle.plugin)
}

kotlin {
    jvmToolchain(libs.versions.java.toolchain.get().toInt())
}
