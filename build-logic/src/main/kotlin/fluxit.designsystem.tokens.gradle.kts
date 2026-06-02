import dev.franzueto.fluxit.icons.GenerateIconsTask
import dev.franzueto.fluxit.icons.VerifyIconsInSyncTask
import dev.franzueto.fluxit.tokens.GenerateTokensTask
import dev.franzueto.fluxit.tokens.VerifyTokensInSyncTask
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// fluxit.designsystem.tokens
//
// Design-system codegen wiring for :core:core-designsystem
// (ADR-005 tokens + ADR-005a icons). Applied on top of fluxit.kmp.library.
// Registers two parallel pipelines:
//
//   - generateTokens          — reads tokens/tokens.json, emits Compose sources
//                               under build/generated/source/tokens/androidMain
//                               and a single FluxItTokens.swift under
//                               <repo>/ios-app/Generated/.
//   - verifyTokensInSync      — re-runs the token generator and asserts every
//                               expected file is present + non-trivial.
//   - generateIcons           — reads icons/*.svg, emits a single FluxItIcons.kt
//                               under build/generated/source/icons/androidMain,
//                               an xcassets bundle under
//                               <repo>/ios-app/Resources/FluxItIcons.xcassets/,
//                               and a Swift accessor file under
//                               <repo>/ios-app/Generated/icons/.
//   - verifyIconsInSync       — re-runs the icon generator and asserts every
//                               expected output is present + non-trivial.
//
// Hooks:
//   - Both generated Compose dirs are registered on the androidMain Kotlin
//     source set so downstream code can import the generated APIs.
//   - Every compile-kotlin task in this module dependsOn both generators, so
//     `./gradlew :core:core-designsystem:assembleDebug` triggers both regens.
//   - The Swift emissions are invoked from scripts/build-ios.sh (one extra
//     `./gradlew :core:core-designsystem:generateTokens generateIcons` line
//     before xcodegen). Adding an Xcode "Run Script" phase is deferred to
//     Phase 15 (CI/CD).

private val tokensJson = layout.projectDirectory.file("tokens/tokens.json")
private val composeDir = layout.buildDirectory.dir("generated/source/tokens/androidMain")
// core/core-designsystem → ../../ios-app/Generated
private val swiftDir = rootProject.layout.projectDirectory.dir("ios-app/Generated")

private val iconsDir = layout.projectDirectory.dir("icons")
private val composeIconsDir = layout.buildDirectory.dir("generated/source/icons/androidMain")
private val xcassetsDir = rootProject.layout.projectDirectory.dir("ios-app/Resources/FluxItIcons.xcassets")
// Sibling subdir of the token Swift output dir so the two tasks don't declare
// overlapping @OutputDirectory paths (Gradle's implicit-dep validator otherwise
// flags this).
private val swiftIconsDir = rootProject.layout.projectDirectory.dir("ios-app/Generated/icons")

private val generateTokens = tasks.register<GenerateTokensTask>("generateTokens") {
    tokensJsonFile.set(tokensJson)
    composeOutputDir.set(composeDir)
    swiftOutputDir.set(swiftDir)
}

private val generateIcons = tasks.register<GenerateIconsTask>("generateIcons") {
    iconsSourceDir.set(iconsDir)
    composeOutputDir.set(composeIconsDir)
    xcassetsOutputDir.set(xcassetsDir)
    swiftAccessorOutputDir.set(swiftIconsDir)
}

extensions.configure<KotlinMultiplatformExtension>("kotlin") {
    sourceSets.named("androidMain") {
        kotlin.srcDir(composeDir)
        kotlin.srcDir(composeIconsDir)
    }
}

tasks.matching { it.name.startsWith("compile") && "Kotlin" in it.name }
    .configureEach {
        dependsOn(generateTokens)
        dependsOn(generateIcons)
    }

// ktlint / detekt scan the androidMain source set, which includes the generated
// token + icon srcDirs registered above. They filter those paths out at execution
// (see fluxit.quality), but Gradle's input snapshot still sees the directories as
// inputs produced by the generators — so without an explicit dependency the
// implicit-dependency validator fails the build whenever a generator is out of
// date (e.g. after editing tokens.json). Wire the dependency so `:check` is sound.
tasks.matching { it.name.startsWith("runKtlint") || it.name.startsWith("detekt") }
    .configureEach {
        dependsOn(generateTokens)
        dependsOn(generateIcons)
    }

tasks.register<VerifyTokensInSyncTask>("verifyTokensInSync") {
    composeOutputDir.set(composeDir)
    swiftOutputDir.set(swiftDir)
    dependsOn(generateTokens)
}

tasks.register<VerifyIconsInSyncTask>("verifyIconsInSync") {
    composeOutputDir.set(composeIconsDir)
    xcassetsOutputDir.set(xcassetsDir)
    swiftAccessorOutputDir.set(swiftIconsDir)
    dependsOn(generateIcons)
}
