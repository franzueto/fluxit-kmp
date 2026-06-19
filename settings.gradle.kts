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

// Module graph (Phase 01 §5–§6). `:features:*` modules are intentionally
// absent — phases 07–10 add them. `:ios-app` lands in §7.

// app
include(":android-app")

// core
include(":core:core-designsystem")
include(":core:core-utils")
project(":core:core-designsystem").projectDir = file("core/core-designsystem")
project(":core:core-utils").projectDir = file("core/core-utils")

// platform
include(":platform:platform-analytics")
include(":platform:platform-config")
include(":platform:platform-logging")
include(":platform:platform-photo")
include(":platform:platform-reminders")
project(":platform:platform-analytics").projectDir = file("platform/platform-analytics")
project(":platform:platform-config").projectDir = file("platform/platform-config")
project(":platform:platform-logging").projectDir = file("platform/platform-logging")
project(":platform:platform-photo").projectDir = file("platform/platform-photo")
project(":platform:platform-reminders").projectDir = file("platform/platform-reminders")

// shared
include(":shared:domain")
include(":shared:domain-testing")
include(":shared:data")
include(":shared:state")
project(":shared:domain").projectDir = file("shared/domain")
project(":shared:domain-testing").projectDir = file("shared/domain-testing")
project(":shared:data").projectDir = file("shared/data")
project(":shared:state").projectDir = file("shared/state")

// features
include(":features:feature-lists")
include(":features:feature-list-detail")
include(":features:feature-create-list")
include(":features:feature-item-detail")
project(":features:feature-lists").projectDir = file("features/feature-lists")
