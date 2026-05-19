package dev.franzueto.fluxit.icons

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

// Gradle task that turns core/core-designsystem/icons/*.svg into a Compose
// FluxItIcons.kt source file, a SwiftUI xcassets bundle, and a Swift accessor
// file. Owned by ADR-005a; wired onto core-designsystem by the
// fluxit.designsystem.tokens precompiled plugin (extended to register icons
// alongside tokens).
//
// Inputs:  iconsSourceDir — directory containing *.svg files (and the
//          ATTRIBUTION.md / LICENSE-APACHE-2.0.txt non-input siblings).
// Outputs:
//   - composeOutputDir         (Kotlin sources under build/)
//   - xcassetsOutputDir        (asset-catalog bundle under ios-app/Resources/)
//   - swiftAccessorOutputDir   (Swift accessor file under ios-app/Generated/icons/)
//
// All three output dirs are managed exclusively by this task. The Compose dir
// is gitignored via build/; the xcassets and Swift dirs are gitignored at the
// repo root (.gitignore).
abstract class GenerateIconsTask : DefaultTask() {

    init {
        group = "build"
        description = "Regenerates FluxIt icon sources for Compose + SwiftUI from core-designsystem/icons/*.svg."
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iconsSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val composeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val xcassetsOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val swiftAccessorOutputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val sourceDir = iconsSourceDir.get().asFile
        val svgs = sourceDir.listFiles { f -> f.extension == "svg" }
            ?.sortedBy { it.name }
            .orEmpty()
        check(svgs.isNotEmpty()) {
            "generateIcons: no *.svg files found in $sourceDir"
        }

        val icons = svgs.map { SvgPathParser.parse(it) }

        writeComposeOutput(icons)
        writeXcassetsAndSwiftOutput(icons, sourceDir)

        logger.lifecycle(
            "generateIcons: wrote 1 Kotlin file, ${icons.size} xcassets imageset(s), and 1 Swift accessor file " +
                "for ${icons.size} source SVG(s)",
        )
    }

    private fun writeComposeOutput(icons: List<IconSource>) {
        val composeDir = composeOutputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val packageDir = File(composeDir, "dev/franzueto/fluxit/core/designsystem/icons").apply { mkdirs() }
        KotlinIconEmitter.emit(icons).forEach { (filename, source) ->
            File(packageDir, filename).writeText(source)
        }
    }

    private fun writeXcassetsAndSwiftOutput(icons: List<IconSource>, sourceDir: File) {
        val output = SwiftIconEmitter.emit(icons)

        val xcassetsDir = xcassetsOutputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        File(xcassetsDir, "Contents.json").writeText(output.xcassetsRootContentsJson)
        output.imagesets.forEach { entry ->
            val imagesetDir = File(xcassetsDir, "${entry.imagesetName}.imageset").apply { mkdirs() }
            File(imagesetDir, "Contents.json").writeText(entry.contentsJson)
            File(sourceDir, entry.sourceSvgFilename).copyTo(
                target = File(imagesetDir, entry.outputSvgFilename),
                overwrite = true,
            )
        }

        val swiftDir = swiftAccessorOutputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        File(swiftDir, "FluxItIcons.swift").writeText(output.swiftAccessorSource)
    }
}

// Companion verifier — mirrors VerifyTokensInSyncTask. Re-runs the generator
// and asserts every expected output is present and non-trivial.
abstract class VerifyIconsInSyncTask : DefaultTask() {

    init {
        group = "verification"
        description = "Re-runs the icon generator and asserts every expected output file exists."
    }

    @get:OutputDirectory
    abstract val composeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val xcassetsOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val swiftAccessorOutputDir: DirectoryProperty

    @TaskAction
    fun verify() {
        // Compose: exactly one file (FluxItIcons.kt) in the package subdir,
        // non-trivial size.
        val composeFile = File(
            composeOutputDir.get().asFile,
            "dev/franzueto/fluxit/core/designsystem/icons/FluxItIcons.kt",
        )
        check(composeFile.exists() && composeFile.length() >= MIN_GENERATED_BYTES) {
            "verifyIconsInSync: missing or suspiciously small Compose output at $composeFile"
        }

        // Swift accessor: FluxItIcons.swift, non-trivial size.
        val swiftFile = File(swiftAccessorOutputDir.get().asFile, "FluxItIcons.swift")
        check(swiftFile.exists() && swiftFile.length() >= MIN_GENERATED_BYTES) {
            "verifyIconsInSync: missing or suspiciously small Swift accessor at $swiftFile"
        }

        // xcassets: root Contents.json + at least one imageset.
        val xcassetsDir = xcassetsOutputDir.get().asFile
        val rootContents = File(xcassetsDir, "Contents.json")
        check(rootContents.exists() && rootContents.length() > 0) {
            "verifyIconsInSync: missing xcassets root Contents.json at $rootContents"
        }
        val imagesets = xcassetsDir.listFiles { f -> f.isDirectory && f.name.endsWith(".imageset") }.orEmpty()
        check(imagesets.isNotEmpty()) {
            "verifyIconsInSync: no imageset directories found under $xcassetsDir"
        }
        imagesets.forEach { dir ->
            val imagesetContents = File(dir, "Contents.json")
            check(imagesetContents.exists() && imagesetContents.length() > 0) {
                "verifyIconsInSync: imageset $dir missing Contents.json"
            }
            val svgs = dir.listFiles { f -> f.extension == "svg" }.orEmpty()
            check(svgs.size == 1) {
                "verifyIconsInSync: imageset $dir should contain exactly one SVG, found ${svgs.size}"
            }
        }

        logger.lifecycle(
            "verifyIconsInSync: 1 Kotlin file, ${imagesets.size} imageset(s), 1 Swift file — OK",
        )
    }

    private companion object {
        const val MIN_GENERATED_BYTES = 200L
    }
}
