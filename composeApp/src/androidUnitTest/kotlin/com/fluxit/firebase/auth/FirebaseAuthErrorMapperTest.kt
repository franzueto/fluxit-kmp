package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Firebase Auth error code/exception -> `AuthError` translation table.
 *
 * Scope bound, deliberate: this JVM suite does not construct Firebase SDK exception
 * types. Their constructors call `android.text.TextUtils`, which throws under the
 * unit-test stub android.jar, and the alternative (`testOptions.unitTests
 * .isReturnDefaultValues = true`) would silently disarm that guard for every unit test
 * in the module. The SDK-exception path is covered instead by `FirebaseAuthEmulator
 * IntegrationTest`, which asserts the mapping against error codes produced by the real
 * SDK talking to the real Auth emulator - strictly better evidence than a hand-built
 * exception. (`FirebaseAuthEmulatorIntegrationTest`, `androidInstrumentedTest`.)
 */
class FirebaseAuthErrorMapperTest {

    @Test
    fun everyModelledErrorCodeMapsToItsTaxonomyCase() {
        val expected = mapOf(
            "ERROR_INVALID_EMAIL" to AuthError.InvalidEmail,
            "ERROR_WRONG_PASSWORD" to AuthError.InvalidCredentials,
            "ERROR_INVALID_CREDENTIAL" to AuthError.InvalidCredentials,
            "ERROR_INVALID_LOGIN_CREDENTIALS" to AuthError.InvalidCredentials,
            "ERROR_EMAIL_ALREADY_IN_USE" to AuthError.EmailAlreadyInUse,
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" to AuthError.EmailAlreadyInUse,
            "ERROR_CREDENTIAL_ALREADY_IN_USE" to AuthError.EmailAlreadyInUse,
            "ERROR_WEAK_PASSWORD" to AuthError.WeakPassword,
            "ERROR_USER_NOT_FOUND" to AuthError.UserNotFound,
            "ERROR_USER_DISABLED" to AuthError.UserDisabled,
            "ERROR_TOO_MANY_REQUESTS" to AuthError.TooManyRequests,
            "ERROR_NETWORK_REQUEST_FAILED" to AuthError.NetworkUnavailable,
            "ERROR_USER_TOKEN_EXPIRED" to AuthError.SessionExpired,
            "ERROR_INVALID_USER_TOKEN" to AuthError.SessionExpired,
            "ERROR_SESSION_EXPIRED" to AuthError.SessionExpired,
            "ERROR_REQUIRES_RECENT_LOGIN" to AuthError.SessionExpired,
        )

        expected.forEach { (code, error) ->
            assertEquals(error, authErrorForCode(code), "unexpected mapping for $code")
        }
    }

    @Test
    fun anUnmodelledCodeHasNoMappingSoTheCallerCanLogItAsUnknown() {
        assertNull(authErrorForCode("ERROR_OPERATION_NOT_ALLOWED"))
        assertNull(authErrorForCode("ERROR_SOMETHING_THE_SDK_ADDS_LATER"))
    }

    @Test
    fun transportFailuresWithoutAnAuthCodeMapToNetworkUnavailable() {
        val diagnostics = RecordingDiagnostics()

        assertEquals(
            AuthError.NetworkUnavailable,
            mapAuthFailure("signIn", IOException("socket closed"), diagnostics),
        )
        assertEquals(
            AuthError.NetworkUnavailable,
            mapAuthFailure("signIn", UnknownHostException("no dns"), diagnostics),
        )
        assertTrue(diagnostics.entries.all { it.recognised && it.errorCode == null })
        assertEquals(2, diagnostics.entries.size)
    }

    @Test
    fun anUnmappedThrowableIsHandedToDiagnosticsBeforeBecomingUnknown() {
        val diagnostics = RecordingDiagnostics()
        val original = RuntimeException("totally unexpected")

        val mapped = mapAuthFailure("signOut", original, diagnostics)

        assertEquals(AuthError.Unknown, mapped)
        assertSame(original, diagnostics.entries.single().throwable)
    }
}
