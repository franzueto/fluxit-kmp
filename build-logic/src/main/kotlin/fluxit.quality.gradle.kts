import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// fluxit.quality
//
// Applies and configures the four static-analysis tools used by every FluxIt
// module: ktlint, detekt, Spotless, and the harness for Konsist tests.
//
// Konsist itself is a JUnit-discoverable assertion library, not a plugin —
// it runs as a regular `test` task in modules that declare Konsist rules.
// This plugin therefore does not "register a Konsist test source set" in the
// Kotlin Multiplatform sense; that source set lives in build-logic's own
// test/ directory (Phase 01 section 8).

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("com.diffplug.spotless")
    id("org.jlleitschuh.gradle.ktlint")
}

private val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
private fun version(alias: String): String =
    versionCatalog.findVersion(alias).orElseThrow {
        IllegalStateException("Missing version alias `$alias` in libs.versions.toml")
    }.requiredVersion

extensions.configure<KtlintExtension>("ktlint") {
    version.set(version("ktlint-cli"))
    android.set(false)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

extensions.configure<DetektExtension>("detekt") {
    toolVersion = version("detekt")
    parallel = true
    buildUponDefaultConfig = true
    autoCorrect = false
    config.setFrom(rootProject.files("config/detekt.yml"))
    source.setFrom(files("src"))
}

extensions.configure<SpotlessExtension>("spotless") {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
        ktlint(version("ktlint-cli"))
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint(version("ktlint-cli"))
    }
    format("markdown") {
        target("**/*.md")
        targetExclude("**/build/**")
        endWithNewline()
        trimTrailingWhitespace()
    }
}
