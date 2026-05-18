package dev.franzueto.fluxit.tokens

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

// Gradle task that turns tokens.json into Compose (Kotlin) and SwiftUI sources.
// Owned by ADR-005; wired onto core-designsystem by the
// fluxit.designsystem.tokens precompiled plugin.
//
// Inputs:  tokens.json (RegularFile).
// Outputs: composeOutputDir (Kotlin sources under build/), swiftOutputDir
//          (Swift source under ios-app/Generated/).
//
// Both output dirs are managed exclusively by this task. The Kotlin dir lives
// inside build/ (gitignored); the Swift dir is gitignored at the repo root
// (.gitignore covers ios-app/Generated/).
abstract class GenerateTokensTask : DefaultTask() {

    init {
        group = "build"
        description = "Regenerates FluxIt design-token sources for Compose + SwiftUI from tokens.json."
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tokensJsonFile: RegularFileProperty

    @get:OutputDirectory
    abstract val composeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val swiftOutputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val doc = TokenParser.parse(tokensJsonFile.get().asFile)

        val composeDir = composeOutputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        KotlinEmitter.emit(doc).forEach { (filename, source) ->
            File(composeDir, filename).writeText(source)
        }

        val swiftDir = swiftOutputDir.get().asFile.apply { mkdirs() }
        // Wipe only our known outputs — don't recursively delete since
        // ios-app/Generated/ may pick up other generated content in the future
        // (Phase 02 §3 fonts land in ios-app/Resources/, but defensive nonetheless).
        SwiftEmitter.emit(doc).forEach { (filename, source) ->
            File(swiftDir, filename).writeText(source)
        }

        logger.lifecycle(
            "generateTokens: wrote ${KotlinEmitter.emit(doc).size} Kotlin file(s) to $composeDir " +
                "and ${SwiftEmitter.emit(doc).size} Swift file(s) to $swiftDir",
        )
    }
}

// Companion verifier: re-runs generation and asserts every expected output is
// present + non-trivial. With our gitignored-generated-files setup the plan's
// original "fails if working tree is dirty" interpretation doesn't apply
// (nothing in the working tree to diff against), so the verifier instead
// guards against tokens.json structural breakage and silent emitter regressions.
abstract class VerifyTokensInSyncTask : DefaultTask() {

    init {
        group = "verification"
        description = "Re-runs the token generator and asserts every expected output file exists."
    }

    @get:OutputDirectory
    abstract val composeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val swiftOutputDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val expectedCompose = setOf(
            "FluxItColors.kt",
            "FluxItSpacing.kt",
            "FluxItShapes.kt",
            "FluxItTypography.kt",
            "FluxItElevation.kt",
        )
        val expectedSwift = setOf("FluxItTokens.swift")

        val composeFiles = composeOutputDir.get().asFile.listFiles().orEmpty()
            .associate { it.name to it.length() }
        val swiftFiles = swiftOutputDir.get().asFile.listFiles().orEmpty()
            .associate { it.name to it.length() }

        val missingCompose = expectedCompose - composeFiles.keys
        val missingSwift = expectedSwift - swiftFiles.keys
        check(missingCompose.isEmpty()) { "verifyTokensInSync: missing Compose outputs: $missingCompose" }
        check(missingSwift.isEmpty()) { "verifyTokensInSync: missing Swift outputs: $missingSwift" }

        val empty = (composeFiles + swiftFiles).filterValues { it < MIN_GENERATED_BYTES }
        check(empty.isEmpty()) {
            "verifyTokensInSync: suspiciously small generated files (<$MIN_GENERATED_BYTES bytes): $empty"
        }

        logger.lifecycle(
            "verifyTokensInSync: ${composeFiles.size + swiftFiles.size} generated files OK",
        )
    }

    private companion object {
        // Any real generated file is far larger than the header banner alone.
        const val MIN_GENERATED_BYTES = 200L
    }
}
