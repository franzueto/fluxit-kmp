import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// fluxit.kmp.library
//
// Convention plugin applied to every shared / core / platform KMP library module
// (anything that is NOT android-app, ios-app, or build-logic itself). Sets up:
//   - Kotlin Multiplatform targets: androidTarget + iosX64 + iosArm64 + iosSimulatorArm64
//   - JVM bytecode target 17 (Android & non-iOS sources)
//   - kotlinx.serialization compiler plugin (opt-in runtime dep per module)
//   - SKIE for Swift-friendly framework export
//   - Android library defaults from the version catalog
//
// Modules can override Android namespace via:
//   android { namespace = "dev.franzueto.fluxit.feature.lists" }

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("co.touchlab.skie")
    id("fluxit.quality")
}

// Kover coverage. Applied to the layers that carry a branch-coverage gate:
// :shared:domain (use cases, ≥95% — Phase 04 §13) and :shared:state (stores,
// ≥90% — Phase 05 §12). Applied from the convention plugin (build-logic
// classpath) rather than the module's `plugins {}` block so Kover shares the
// Kotlin Multiplatform plugin's classloader and the `kotlin.native.bundle.type`
// attribute is registered once. The per-module `kover { }` verify rule lives in
// each module's build.gradle.kts.
if (path == ":shared:domain" || path == ":shared:state") {
    pluginManager.apply("org.jetbrains.kotlinx.kover")
}

private val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
private fun version(alias: String): String =
    versionCatalog.findVersion(alias).orElseThrow {
        IllegalStateException("Missing version alias `$alias` in libs.versions.toml")
    }.requiredVersion

kotlin {
    jvmToolchain(version("java-toolchain").toInt())

    androidTarget {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = project.name.replace("-", "")
            isStatic = true
        }
    }

    targets.configureEach {
        if (platformType != KotlinPlatformType.androidJvm) {
            compilations.configureEach {
                compileTaskProvider.configure {
                    compilerOptions {
                        // Force opt-in errors to surface early in shared code.
                        freeCompilerArgs.addAll(
                            "-Xexpect-actual-classes",
                        )
                    }
                }
            }
        }
    }
}

android {
    compileSdk = version("android-compile-sdk").toInt()
    namespace = "dev.franzueto.fluxit." + project.name.replace("-", "").lowercase()

    defaultConfig {
        minSdk = version("android-min-sdk").toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
