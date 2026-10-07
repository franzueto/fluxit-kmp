package com.fluxit.di

import com.fluxit.config.FirebaseDevFlags
import kotlin.test.Test
import kotlin.test.assertTrue

/** A retired selection property must not re-enable Room in ordinary builds. */
class FirebaseDevFlagsTest {
    @Test fun repositoryMetadataAlwaysReportsFirebase() {
        assertTrue(FirebaseDevFlags.USE_FIREBASE_REPOSITORIES)
    }
}
