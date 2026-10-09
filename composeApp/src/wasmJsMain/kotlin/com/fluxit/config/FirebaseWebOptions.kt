package com.fluxit.config

/**
 * The Firebase web app options, as shown in Console → Project settings → Your apps → Web app.
 * Supplied at build time by the generated [FirebaseWebConfig]. These values identify the
 * project; they are not credentials, but they stay out of git like the mobile configs.
 */
internal data class FirebaseWebOptions(
    val apiKey: String,
    val authDomain: String,
    val projectId: String,
    val storageBucket: String,
    val messagingSenderId: String,
    val appId: String,
) {
    companion object {
        /**
         * Used instead of the web config by emulator builds. `demo-*` project IDs are reserved
         * for the Emulator Suite: no real project has one, so a request that misses the emulator
         * fails instead of reaching a real project. The emulators run as `demo-fluxit`, the
         * `.firebaserc` default, so starting them needs no `--project` and no real config.
         */
        val Emulator = FirebaseWebOptions(
            apiKey = "demo-api-key",
            authDomain = "demo-fluxit.firebaseapp.com",
            projectId = "demo-fluxit",
            storageBucket = "demo-fluxit.appspot.com",
            messagingSenderId = "0",
            appId = "demo-app-id",
        )

        /** The options a build starts Firebase with: [Emulator] for emulator builds, else [configured]. */
        fun forBuild(emulatorEnabled: Boolean, configured: FirebaseWebOptions?): FirebaseWebOptions? =
            if (emulatorEnabled) Emulator else configured
    }
}
