package com.fluxit.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * FB-207: [selectRepositoryBinding] is the one piece of the repository-wiring dev-flag
 * both `actual platformModule()`s share, so its two properties are proven once here
 * instead of per platform:
 *
 * - it returns the branch the flag names, and
 * - it never invokes the *other* branch - in particular, with the flag off (the shipped
 *   default), the Firebase branch is never even constructed, so no Firestore handle, no
 *   listener, and no uid resolution can happen from this call alone.
 *
 * Runs on both Android (`testDebugUnitTest`/`testReleaseUnitTest`) and iOS
 * (`iosSimulatorArm64Test`) as part of `commonTest`.
 */
class RepositoryBindingTest {

    @Test
    fun flagOffSelectsRoomAndNeverConstructsTheFirebaseBranch() {
        var firebaseConstructed = false

        val result = selectRepositoryBinding(
            useFirebaseRepositories = false,
            firebase = {
                firebaseConstructed = true
                "firebase"
            },
            room = { "room" },
        )

        assertEquals("room", result)
        assertFalse(firebaseConstructed, "flag off must not construct the Firebase branch")
    }

    @Test
    fun flagOnSelectsFirebaseAndNeverConstructsTheRoomBranch() {
        var roomConstructed = false

        val result = selectRepositoryBinding(
            useFirebaseRepositories = true,
            firebase = { "firebase" },
            room = {
                roomConstructed = true
                "room"
            },
        )

        assertEquals("firebase", result)
        assertFalse(roomConstructed, "flag on must not construct the Room branch")
    }
}
