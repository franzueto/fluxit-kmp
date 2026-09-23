package com.fluxit.di

import com.fluxit.config.FirebaseDevFlags
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * FB-207: guards the shipped default of the `fluxit.firebase.repositories.enabled` Gradle
 * property (`composeApp/build.gradle.kts`'s `generateFirebaseEmulatorConfig` task,
 * `gradle.properties`). If this ever flips to `true` by accident, every
 * `actual platformModule()` starts binding the Firebase repositories in ordinary builds -
 * this is Phase 7's production cutover, not something that should happen silently as a
 * side effect of an unrelated `gradle.properties` edit.
 */
class FirebaseDevFlagsTest {

    @Test
    fun theFirebaseRepositoriesFlagDefaultsToOff() {
        assertFalse(FirebaseDevFlags.USE_FIREBASE_REPOSITORIES)
    }
}
