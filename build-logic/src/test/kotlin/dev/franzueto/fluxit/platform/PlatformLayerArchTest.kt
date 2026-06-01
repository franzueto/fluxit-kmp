package dev.franzueto.fluxit.platform

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import io.kotest.core.spec.style.FunSpec

// FluxIt platform-layer architecture rule — Phase 06 §0 + Definition-of-Done.
//
// The OS-capability SDKs must stay encapsulated inside the :platform:* modules so
// the rest of the app sees capabilities only through the :shared:domain ports.
// Nothing outside platform/ (and outside test/build sources) may import
// WorkManager, UserNotifications, CameraX, Photos, Firebase, or kermit-crashlytics.
//
// Runs as a regular JUnit 5 test in :build-logic; scans the OUTER repo. Mirrors
// DataLayerArchTest / StateLayerArchTest.
class PlatformLayerArchTest : FunSpec({

    val scope = { Konsist.scopeFromDirectory("..") }

    val bannedPrefixes =
        listOf(
            "androidx.work.",
            "androidx.camera.",
            "platform.UserNotifications",
            "platform.Photos",
            "platform.AVFoundation",
            "com.google.firebase",
            "co.touchlab.kermit.crashlytics",
        )

    test("OS-capability SDKs are imported only inside :platform:* modules") {
        scope()
            .files
            .filter { file ->
                "/build/" !in file.path &&
                    "/build-logic/" !in file.path &&
                    "/platform/" !in file.path
            }
            .assertFalse(
                additionalMessage =
                    "WorkManager / UserNotifications / CameraX / Photos / Firebase / kermit-crashlytics " +
                        "may be imported only inside :platform:* modules. The rest of the app depends on the " +
                        ":shared:domain capability ports (AppLogger, ReminderScheduler, PhotoCapture, …).",
            ) { file ->
                file.imports.any { imp -> bannedPrefixes.any { imp.name.startsWith(it) } }
            }
    }
})
