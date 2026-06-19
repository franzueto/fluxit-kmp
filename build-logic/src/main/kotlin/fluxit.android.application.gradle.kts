import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// fluxit.android.application
//
// Applied to /android-app. Configures the Android application module: app id,
// SDK levels, Compose, R8 in release, and the FluxIt quality gate suite.
//
// Real release signing is wired in Phase 17 (Release Hardening); for now the
// release build type uses a placeholder signing config that falls back to the
// debug keystore so `assembleRelease` does not fail during early development.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("fluxit.quality")
}

private val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
private fun version(alias: String): String =
    versionCatalog.findVersion(alias).orElseThrow {
        IllegalStateException("Missing version alias `$alias` in libs.versions.toml")
    }.requiredVersion

kotlin {
    jvmToolchain(version("java-toolchain").toInt())
}

android {
    namespace = "dev.franzueto.fluxit"
    compileSdk = version("android-compile-sdk").toInt()

    defaultConfig {
        applicationId = "dev.franzueto.fluxit"
        minSdk = version("android-min-sdk").toInt()
        targetSdk = version("android-target-sdk").toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Placeholder signing — Phase 17 swaps in the real release keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
            )
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        if (this is org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions) {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
