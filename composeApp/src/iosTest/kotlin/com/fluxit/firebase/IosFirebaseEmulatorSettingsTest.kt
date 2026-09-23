package com.fluxit.firebase

import com.fluxit.config.FirebaseEmulatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FB-203 discharges `FB-007-NB1`: no automated test previously covered
 * [IosFirebaseEmulatorSettings], the Swift-facing emulator-config seam FB-007 added.
 * The one real risk the FB-007/FB-202 reviewers identified was this seam silently
 * reapplying Android's `10.0.2.2` host-translation rule on iOS, or otherwise drifting
 * from the generated [FirebaseEmulatorConfig] single source of truth. This test asserts
 * every field is passed through **verbatim** - not just present, but value-for-value
 * equal - which would fail immediately if either regression were introduced.
 */
class IosFirebaseEmulatorSettingsTest {

    @Test
    fun everyFieldEqualsTheGeneratedEmulatorConfigVerbatim() {
        assertEquals(FirebaseEmulatorConfig.ENABLED, IosFirebaseEmulatorSettings.enabled)
        assertEquals(FirebaseEmulatorConfig.HOST, IosFirebaseEmulatorSettings.host)
        assertEquals(FirebaseEmulatorConfig.AUTH_PORT, IosFirebaseEmulatorSettings.authPort)
        assertEquals(FirebaseEmulatorConfig.FIRESTORE_PORT, IosFirebaseEmulatorSettings.firestorePort)
        assertEquals(FirebaseEmulatorConfig.STORAGE_PORT, IosFirebaseEmulatorSettings.storagePort)
    }

    @Test
    fun theHostIsNeverGivenTheAndroidEmulatorLoopbackTranslation() {
        // Android's AndroidFirebaseInitializer rewrites 127.0.0.1 -> 10.0.2.2 because the
        // Android emulator is a separate VM; the iOS simulator shares the host's network
        // stack and must NOT apply that rewrite (see IosFirebaseEmulatorSettings's KDoc).
        // Asserting exact equality with the raw generated HOST value (rather than just
        // "is a string") is what would catch a future accidental copy-paste of the
        // Android translation onto this seam.
        assertEquals(FirebaseEmulatorConfig.HOST, IosFirebaseEmulatorSettings.host)
    }
}
