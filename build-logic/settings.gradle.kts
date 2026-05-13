// build-logic is an included build: it produces the precompiled convention plugins
// (fluxit.kmp.library, fluxit.kmp.feature, fluxit.android.application, fluxit.quality)
// that every other module applies. It is wired into the root build via
// `includeBuild("build-logic")` in the root settings.gradle.kts.

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
