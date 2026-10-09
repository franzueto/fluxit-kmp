package com.fluxit.firebase

import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.config.FirebaseWebConfig
import com.fluxit.config.FirebaseWebOptions

/**
 * Starts the Firebase JS SDK on first use, from the generated web options and the shared
 * emulator constants (the web counterpart of `FirebaseBootstrap.start()` on iOS). Emulator
 * builds use [FirebaseWebOptions.Emulator] (`demo-fluxit`) instead of the web config.
 *
 * Lazy so that merely building the Koin graph, or loading the test bundle, never
 * initializes Firebase.
 */
internal object WebFirebase {

    private var started = false

    fun ensureStarted() {
        if (started) return
        val options = checkNotNull(FirebaseWebOptions.forBuild(FirebaseEmulatorConfig.ENABLED, FirebaseWebConfig.options)) {
            "This build has no Firebase web config. Copy composeApp/firebase-web-config.example.json " +
                "to composeApp/firebase-web-config.json and fill it in, then rebuild."
        }
        initializeFirebase(
            apiKey = options.apiKey,
            authDomain = options.authDomain,
            projectId = options.projectId,
            storageBucket = options.storageBucket,
            messagingSenderId = options.messagingSenderId,
            appId = options.appId,
            emulatorHost = if (FirebaseEmulatorConfig.ENABLED) FirebaseEmulatorConfig.HOST else null,
            authPort = FirebaseEmulatorConfig.AUTH_PORT,
            firestorePort = FirebaseEmulatorConfig.FIRESTORE_PORT,
            storagePort = FirebaseEmulatorConfig.STORAGE_PORT,
        )
        started = true
    }
}
