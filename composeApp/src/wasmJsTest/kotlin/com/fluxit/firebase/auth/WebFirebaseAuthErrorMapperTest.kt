package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mapping table for Firebase JS SDK auth codes; follows the iOS table for email/password
 * (differences listed on [authErrorForFirebaseCode]).
 * Asserts the mapping, not that the JS SDK emits these codes; the emulator run recorded
 * in docs/web-app/PROGRESS.md covers the common ones.
 */
class WebFirebaseAuthErrorMapperTest {

    @Test
    fun firebaseAuthCodesMapToTheSharedTaxonomy() {
        assertEquals(AuthError.InvalidEmail, authErrorForFirebaseCode("auth/invalid-email"))
        assertEquals(AuthError.InvalidCredentials, authErrorForFirebaseCode("auth/invalid-credential"))
        assertEquals(AuthError.InvalidCredentials, authErrorForFirebaseCode("auth/invalid-login-credentials"))
        assertEquals(AuthError.InvalidCredentials, authErrorForFirebaseCode("auth/wrong-password"))
        assertEquals(AuthError.InvalidCredentials, authErrorForFirebaseCode("auth/user-mismatch"))
        assertEquals(AuthError.EmailAlreadyInUse, authErrorForFirebaseCode("auth/email-already-in-use"))
        assertEquals(AuthError.EmailAlreadyInUse, authErrorForFirebaseCode("auth/account-exists-with-different-credential"))
        assertEquals(AuthError.EmailAlreadyInUse, authErrorForFirebaseCode("auth/credential-already-in-use"))
        assertEquals(AuthError.WeakPassword, authErrorForFirebaseCode("auth/weak-password"))
        assertEquals(AuthError.UserNotFound, authErrorForFirebaseCode("auth/user-not-found"))
        assertEquals(AuthError.UserDisabled, authErrorForFirebaseCode("auth/user-disabled"))
        assertEquals(AuthError.TooManyRequests, authErrorForFirebaseCode("auth/too-many-requests"))
        assertEquals(AuthError.NetworkUnavailable, authErrorForFirebaseCode("auth/network-request-failed"))
        assertEquals(AuthError.NetworkUnavailable, authErrorForFirebaseCode("auth/timeout"))
        assertEquals(AuthError.SessionExpired, authErrorForFirebaseCode("auth/requires-recent-login"))
        assertEquals(AuthError.SessionExpired, authErrorForFirebaseCode("auth/invalid-user-token"))
        assertEquals(AuthError.SessionExpired, authErrorForFirebaseCode("auth/user-token-expired"))
    }

    @Test
    fun unmodelledCodesHaveNoMapping() {
        assertNull(authErrorForFirebaseCode("auth/internal-error"))
        // Client sign-up disabled in the project (decision D3) is not a modelled case.
        assertNull(authErrorForFirebaseCode("auth/admin-restricted-operation"))
        assertNull(authErrorForFirebaseCode("unknown"))
        assertNull(authErrorForFirebaseCode("AUTH/INVALID-EMAIL"))
    }

    @Test
    fun aMappedFailureIsReportedAsRecognised() {
        val diagnostics = RecordingDiagnostics()

        val mapped = mapAuthFailure("signIn", authError("auth/network-request-failed"), diagnostics)

        assertEquals(AuthError.NetworkUnavailable, mapped)
        val entry = diagnostics.entries.single()
        assertEquals("signIn", entry.operation)
        assertTrue(entry.recognised)
    }

    @Test
    fun anUnmappedFailureIsLoggedWithItsOriginalCodeBeforeBecomingUnknown() {
        val diagnostics = RecordingDiagnostics()

        val mapped = mapAuthFailure("signOut", authError("auth/internal-error"), diagnostics)

        assertEquals(AuthError.Unknown, mapped)
        val entry = diagnostics.entries.single()
        assertEquals("auth/internal-error", entry.code)
        assertEquals(AuthError.Unknown, entry.mapped)
        assertFalse(entry.recognised)
    }
}
