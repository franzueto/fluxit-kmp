import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("fluxit.kmp.library")
}

android {
    namespace = "dev.franzueto.fluxit.shared.state"
}

// :shared:state is the iOS-facing entry point — ios-app embeds Shared.xcframework
// assembled from this module's three iOS targets. The XCFramework name drives the
// Gradle task names: `assembleSharedXCFramework`, `assembleSharedReleaseXCFramework`,
// `assembleSharedDebugXCFramework`.
kotlin {
    val sharedXcf = XCFramework("Shared")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.withType<Framework>().configureEach {
            baseName = "Shared"
            sharedXcf.add(this)
        }
    }
}
