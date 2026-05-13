pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "fluxit"

// Module includes. Phase 01 section 5 expands this to the full module graph;
// for now only the verification stub from section 4 is wired in.
include(":core:core-utils")
project(":core:core-utils").projectDir = file("core/core-utils")
