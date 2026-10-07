package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSURLErrorDomain

/**
 * mapping table tests. Kept separate from the adapter tests so the whole
 * `FIRAuthErrorDomain` code -> [AuthError] table is checkable without constructing a
 * repository, exactly as did for the Android string codes.
 *
 * Honest bound: these assert the *mapping*, not that the Apple SDK emits these codes for
 * these situations. The subset confirmed against real emulator-emitted codes is recorded
 * in the evidence.
 */
class IosFirebaseAuthErrorMapperTest {

    /** `NSURLErrorDomain` is typed nullable by cinterop; it is never null at runtime. */
    private val urlErrorDomain: String = requireNotNull(NSURLErrorDomain)

    @Test
    fun firebaseAuthCodesMapToTheSharedTaxonomy() {
        assertEquals(AuthError.InvalidEmail, authErrorForFirebaseCode(17008L))
        assertEquals(AuthError.InvalidCredentials, authErrorForFirebaseCode(17004L))
        assertEquals(AuthError.InvalidCredentials, authErrorForFirebaseCode(17009L))
        assertEquals(AuthError.EmailAlreadyInUse, authErrorForFirebaseCode(17007L))
        assertEquals(AuthError.WeakPassword, authErrorForFirebaseCode(17026L))
        assertEquals(AuthError.UserNotFound, authErrorForFirebaseCode(17011L))
        assertEquals(AuthError.UserDisabled, authErrorForFirebaseCode(17005L))
        assertEquals(AuthError.TooManyRequests, authErrorForFirebaseCode(17010L))
        assertEquals(AuthError.NetworkUnavailable, authErrorForFirebaseCode(17020L))
        assertEquals(AuthError.SessionExpired, authErrorForFirebaseCode(17021L))
        assertEquals(AuthError.SessionExpired, authErrorForFirebaseCode(17017L))
        assertEquals(AuthError.SessionExpired, authErrorForFirebaseCode(17014L))
    }

    @Test
    fun anUnmodelledFirebaseCodeHasNoMapping() {
        assertNull(authErrorForFirebaseCode(17999L)) // internalError
        assertNull(authErrorForFirebaseCode(17995L)) // keychainError
    }

    @Test
    fun transportFailuresOutsideTheAuthDomainMapToNetworkUnavailable() {
        val diagnostics = RecordingDiagnostics()
        val mapped = mapAuthFailure(
            "signIn",
            authError(-1009L, urlErrorDomain), // notConnectedToInternet
            diagnostics,
        )
        assertEquals(AuthError.NetworkUnavailable, mapped)
        assertTrue(diagnostics.entries.single().recognised)
    }

    @Test
    fun anAppTransportSecurityFailureIsDeliberatelyNotDisguisedAsNetworkUnavailable() {
        // -1022 means the app's own ATS configuration rejected the request. Reporting it
        // as NetworkUnavailable would tell a developer to check their wifi instead of
        // their Info.plist, so it collapses to Unknown and is logged loudly instead.
        val diagnostics = RecordingDiagnostics()
        val mapped = mapAuthFailure("signIn", authError(-1022L, urlErrorDomain), diagnostics)
        assertEquals(AuthError.Unknown, mapped)
        assertFalse(diagnostics.entries.single().recognised)
    }

    @Test
    fun anErrorFromAnUnknownDomainIsUnknownAndLoggedLoudly() {
        val diagnostics = RecordingDiagnostics()
        val mapped = mapAuthFailure("signUp", authError(17008L, "SomeOtherDomain"), diagnostics)
        assertEquals(AuthError.Unknown, mapped)
        val entry = diagnostics.entries.single()
        assertFalse(entry.recognised)
        assertEquals("SomeOtherDomain", entry.domain)
        assertEquals(17008L, entry.code)
    }

    @Test
    fun diagnosticsAreReportedBeforeTheErrorIsCollapsed() {
        val diagnostics = RecordingDiagnostics()
        mapAuthFailure("restoreSession", authError(17999L), diagnostics)
        val entry = diagnostics.entries.single()
        assertEquals("restoreSession", entry.operation)
        assertEquals(AuthError.Unknown, entry.mapped)
        assertEquals(17999L, entry.code)
    }
}
