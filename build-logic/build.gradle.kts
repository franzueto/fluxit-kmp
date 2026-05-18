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
    implementation(libs.build.kotlin.serialization.gradle.plugin)
    implementation(libs.build.kotlin.compose.gradle.plugin)
    implementation(libs.build.android.gradle.plugin)
    implementation(libs.build.skie.gradle.plugin)
    implementation(libs.build.sqldelight.gradle.plugin)
    implementation(libs.build.ksp.gradle.plugin)
    implementation(libs.build.detekt.gradle.plugin)
    implementation(libs.build.spotless.gradle.plugin)
    implementation(libs.build.ktlint.gradle.plugin)

    // Used by the token-pipeline parser/emitters in
    // src/main/kotlin/dev/franzueto/fluxit/tokens/ — see ADR-005.
    // We only use Json.parseToJsonElement + JsonObject traversal, so the
    // kotlin-serialization compiler plugin isn't needed.
    implementation(libs.kotlinx.serialization.json)

    // Konsist architecture tests live here so they have a single canonical
    // home — see fluxit.quality.gradle.kts. JUnit 5 is the test runtime.
    testImplementation(libs.konsist)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.runner.junit5)
}

kotlin {
    jvmToolchain(libs.versions.java.toolchain.get().toInt())
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // The Konsist arch tests resolve scopeFromDirectory("..") against the
    // test working directory — which Gradle sets to build-logic/ by default.
    // Made explicit here so reviewers don't have to know that convention.
    workingDir = layout.projectDirectory.asFile
}
