package com.fluxit.firebase

import com.fluxit.firebase.config.FirebaseEmulatorConfig

/**
 * FB-007 Swift-facing seam for the generated [FirebaseEmulatorConfig] constants.
 *
 * The Firebase Apple SDK is integrated on the Xcode side (Swift Package Manager)
 * rather than through Kotlin/Native cinterop, because `FirebaseStorage` and most of
 * `FirebaseAuth` are Swift-only modules that cinterop cannot consume. Consequently
 * the Apple SDK's `useEmulator` calls live in `iosApp/iosApp/FirebaseBootstrap.swift`,
 * and this object is how that Swift code reads the *same* build-time emulator
 * configuration the Android adapter uses, so the two platforms can never drift.
 *
 * Contains no Firebase SDK types on either side of the boundary - only plain values.
 * Nothing here is exposed to `commonMain`.
 *
 * Unlike [com.fluxit.firebase.AndroidFirebaseInitializer], this performs **no** host
 * translation. The Android emulator is a separate virtual machine and must reach the
 * development host through the `10.0.2.2` loopback alias; the iOS simulator shares the
 * host's network stack, so `127.0.0.1` already refers to the machine running the
 * emulator suite and must be passed through unchanged. A physical iOS device does not
 * share that network stack, so a device developer must set
 * `fluxit.firebase.emulator.host` to the machine's LAN address explicitly.
 */
object IosFirebaseEmulatorSettings {

    /** True when the build was configured with `fluxit.firebase.emulator.enabled=true`. */
    val enabled: Boolean = FirebaseEmulatorConfig.ENABLED

    /** Emulator host, used verbatim - see the class note on simulator networking. */
    val host: String = FirebaseEmulatorConfig.HOST

    val authPort: Int = FirebaseEmulatorConfig.AUTH_PORT

    val firestorePort: Int = FirebaseEmulatorConfig.FIRESTORE_PORT

    val storagePort: Int = FirebaseEmulatorConfig.STORAGE_PORT
}
