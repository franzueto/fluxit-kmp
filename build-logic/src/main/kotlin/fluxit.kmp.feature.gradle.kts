import org.gradle.api.artifacts.VersionCatalogsExtension

// fluxit.kmp.feature
//
// Applied to every module under /features/* (and any other module whose role is
// "feature presentation state"). Extends fluxit.kmp.library and pre-wires the
// dependencies every feature module is expected to use:
//   - Koin (DI)
//   - Kermit (logging)
//   - kotlinx-datetime (time, no java.time on iOS)
//   - Turbine + coroutines-test in commonTest
//
// The Konsist rule that forbids `feature-*` modules from depending on each
// other lives in build-logic's own test sources (Phase 01 section 8) — it is a
// graph-wide invariant, not a per-module configuration, so it is not declared
// here. Modules still apply this plugin to opt into the prewired dependency
// stack and Konsist will catch violations at build time.

plugins {
    id("fluxit.kmp.library")
}

private val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
private fun library(alias: String) =
    versionCatalog.findLibrary(alias).orElseThrow {
        IllegalStateException("Missing library alias `$alias` in libs.versions.toml")
    }

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(library("koin-core"))
                implementation(library("kermit"))
                implementation(library("kotlinx-datetime"))
                implementation(library("kotlinx-coroutines-core"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(library("kotlinx-coroutines-test"))
                implementation(library("turbine"))
                implementation(library("kotest-assertions"))
                implementation(library("kotest-framework-engine"))
            }
        }
    }
}
