import dev.franzueto.fluxit.tokens.GenerateTokensTask
import dev.franzueto.fluxit.tokens.VerifyTokensInSyncTask
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// fluxit.designsystem.tokens
//
// Token-pipeline wiring for :core:core-designsystem (ADR-005). Applied on top
// of fluxit.kmp.library. Registers:
//
//   - generateTokens          — reads tokens/tokens.json, emits Compose sources
//                               under build/generated/source/tokens/androidMain
//                               and a single FluxItTokens.swift under
//                               <repo>/ios-app/Generated/.
//   - verifyTokensInSync      — re-runs the generator and asserts every
//                               expected file is present + non-trivial.
//
// Hooks:
//   - The Compose output dir is added to the androidMain Kotlin source set so
//     downstream Compose code can `import dev.franzueto.fluxit.core.designsystem.tokens.*`.
//   - Every compile-kotlin task in this module dependsOn(generateTokens), so a
//     plain `./gradlew :core:core-designsystem:assembleDebug` triggers regen.
//   - The Swift emission is invoked from scripts/build-ios.sh (one extra
//     `./gradlew :core:core-designsystem:generateTokens` line before xcodegen).
//     Adding an Xcode "Run Script" phase is deferred to Phase 15 (CI/CD).

private val tokensJson = layout.projectDirectory.file("tokens/tokens.json")
private val composeDir = layout.buildDirectory.dir("generated/source/tokens/androidMain")
// core/core-designsystem → ../../ios-app/Generated
private val swiftDir = rootProject.layout.projectDirectory.dir("ios-app/Generated")

private val generateTokens = tasks.register<GenerateTokensTask>("generateTokens") {
    tokensJsonFile.set(tokensJson)
    composeOutputDir.set(composeDir)
    swiftOutputDir.set(swiftDir)
}

extensions.configure<KotlinMultiplatformExtension>("kotlin") {
    sourceSets.named("androidMain") {
        kotlin.srcDir(composeDir)
    }
}

tasks.matching { it.name.startsWith("compile") && "Kotlin" in it.name }
    .configureEach { dependsOn(generateTokens) }

tasks.register<VerifyTokensInSyncTask>("verifyTokensInSync") {
    composeOutputDir.set(composeDir)
    swiftOutputDir.set(swiftDir)
    dependsOn(generateTokens)
}
