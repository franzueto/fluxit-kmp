package com.fluxit.firebase.auth

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.errorOrNull
import com.fluxit.domain.auth.isSuccess
import com.fluxit.domain.auth.uidOrNull
import com.fluxit.config.FirebaseEmulatorConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FB-102 Android integration checks: the real Firebase Android Auth SDK, driven through
 * [AndroidAuthRepository], against a real Firebase Auth emulator.
 *
 * Deliberately *not* against the live development project: every account this suite
 * creates is thrown away with the emulator process, nothing reaches the Console, and no
 * credential of any kind lives in this file. The SDK is pointed at the emulator through
 * a dedicated secondary [FirebaseApp] built from throwaway options, so the app's own
 * default [FirebaseApp] (configured from the gitignored `google-services.json`) is never
 * touched and cannot accidentally be the one that talks to a backend here.
 *
 * Prerequisites, because this test cannot provision them itself:
 *
 * 1. an Android emulator/device with `adb reverse tcp:9099 tcp:9099` when the host is
 *    not reachable via `10.0.2.2` (a stock AVD is);
 * 2. the Auth emulator running on the host, from the repository root:
 *    `firebase/node_modules/.bin/firebase emulators:start --only auth --project demo-fluxit`
 *    (ports come from `firebase.json`; the client side reads
 *    [FirebaseEmulatorConfig], the FB-006 single source of truth).
 *
 * Run with: `./gradlew :composeApp:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseAuthEmulatorIntegrationTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var repository: AndroidAuthRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun connectToTheAuthEmulator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FirebaseApp.getApps(context)
            .firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    // Throwaway values: the Auth emulator accepts any key/app id, and a
                    // `demo-` project id can never resolve to a real Firebase project.
                    .setApiKey("fb102-instrumented-test-key")
                    .setApplicationId("1:0:android:fb102")
                    .setProjectId("demo-fluxit")
                    .build(),
                APP_NAME,
            )
        auth = FirebaseAuth.getInstance(app).apply {
            if (!emulatorConfigured) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.AUTH_PORT)
                emulatorConfigured = true
            }
            signOut()
        }
        repository = AndroidAuthRepository(FirebaseSdkAuthGateway(auth), LogcatAuthDiagnostics)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun signOutAndRelease() {
        auth.signOut()
        scope.cancel()
    }

    // --- happy paths --------------------------------------------------------------

    @Test
    fun signUpCreatesAnAccountAndResolvesAnAuthenticatedSession() = runBlocking {
        val email = uniqueEmail()

        val result = repository.signUp(email, PASSWORD)

        assertTrue(result.isSuccess, "sign-up failed: ${result.errorOrNull}")
        val session = repository.awaitResolvedSession()
        assertIs<AuthSession.Authenticated>(session)
        assertNotNull(session.uidOrNull)
        assertEquals(email, session.user.email)
    }

    @Test
    fun signInWithTheCorrectPasswordAuthenticatesTheSameUid() = runBlocking {
        val email = uniqueEmail()
        assertTrue(repository.signUp(email, PASSWORD).isSuccess)
        val uid = repository.awaitResolvedSession().uidOrNull
        assertTrue(repository.signOut().isSuccess)

        val result = repository.signIn(email, PASSWORD)

        assertTrue(result.isSuccess, "sign-in failed: ${result.errorOrNull}")
        assertEquals(uid, repository.awaitResolvedSession().uidOrNull)
    }

    @Test
    fun signOutResolvesSignedOutAndDropsTheUid() = runBlocking {
        assertTrue(repository.signUp(uniqueEmail(), PASSWORD).isSuccess)
        assertIs<AuthSession.Authenticated>(repository.awaitResolvedSession())

        assertTrue(repository.signOut().isSuccess)

        val session = repository.awaitResolvedSession()
        assertEquals(AuthSession.SignedOut, session)
        assertEquals(null, session.uidOrNull)
    }

    @Test
    fun passwordRecoveryIsAcceptedForAKnownAccountAndLeavesTheSessionAlone() = runBlocking {
        val email = uniqueEmail()
        assertTrue(repository.signUp(email, PASSWORD).isSuccess)
        assertTrue(repository.signOut().isSuccess)

        val result = repository.sendPasswordResetEmail(email)

        assertTrue(result.isSuccess, "recovery failed: ${result.errorOrNull}")
        assertEquals(AuthSession.SignedOut, repository.awaitResolvedSession())
    }

    // --- restoration ----------------------------------------------------------------

    @Test
    fun aFreshRepositoryOverThePersistedCredentialRestoresTheSameUid() = runBlocking {
        val email = uniqueEmail()
        assertTrue(repository.signUp(email, PASSWORD).isSuccess)
        val uid = repository.awaitResolvedSession().uidOrNull
        assertNotNull(uid)

        // A new adapter over the same persisted SDK credential: this is what app start
        // after a process death looks like.
        val restored = AndroidAuthRepository(FirebaseSdkAuthGateway(auth), LogcatAuthDiagnostics)
        restored.restoreSession()

        assertEquals(uid, restored.awaitResolvedSession().uidOrNull)
    }

    @Test
    fun restoringWithNoPersistedCredentialResolvesSignedOutNotUnresolved() = runBlocking {
        val fresh = AndroidAuthRepository(FirebaseSdkAuthGateway(auth), LogcatAuthDiagnostics)

        fresh.restoreSession()

        assertEquals(AuthSession.SignedOut, fresh.awaitResolvedSession())
    }

    // --- error taxonomy against real SDK error codes -----------------------------------

    @Test
    fun signingUpTwiceReportsEmailAlreadyInUse() = runBlocking {
        val email = uniqueEmail()
        assertTrue(repository.signUp(email, PASSWORD).isSuccess)
        assertTrue(repository.signOut().isSuccess)

        val result = repository.signUp(email, PASSWORD)

        assertEquals(AuthError.EmailAlreadyInUse, result.errorOrNull)
    }

    @Test
    fun signingUpWithAWeakPasswordReportsWeakPassword() = runBlocking {
        val result = repository.signUp(uniqueEmail(), "123")

        assertEquals(AuthError.WeakPassword, result.errorOrNull)
    }

    @Test
    fun signingUpWithAMalformedEmailReportsInvalidEmail() = runBlocking {
        val result = repository.signUp("not-an-email", PASSWORD)

        assertEquals(AuthError.InvalidEmail, result.errorOrNull)
    }

    @Test
    fun signingInWithTheWrongPasswordReportsInvalidCredentials() = runBlocking {
        val email = uniqueEmail()
        assertTrue(repository.signUp(email, PASSWORD).isSuccess)
        assertTrue(repository.signOut().isSuccess)

        val result = repository.signIn(email, "definitely-not-$PASSWORD")

        assertEquals(AuthError.InvalidCredentials, result.errorOrNull)
    }

    @Test
    fun recoveringAnUnknownAccountReportsUserNotFound() = runBlocking {
        val result = repository.sendPasswordResetEmail(uniqueEmail())

        assertEquals(AuthError.UserNotFound, result.errorOrNull)
    }

    @Test
    fun aFailedOperationLeavesTheSessionUntouched() = runBlocking {
        val email = uniqueEmail()
        assertTrue(repository.signUp(email, PASSWORD).isSuccess)
        val uid = repository.awaitResolvedSession().uidOrNull

        val result = repository.signIn(email, "wrong-password")

        assertIs<AuthResult.Failure>(result)
        assertEquals(uid, repository.awaitResolvedSession().uidOrNull)
    }

    // --- FB-101-NB3 against the real SDK listener --------------------------------------

    @Test
    fun cancellingTheCollectorReleasesTheRealSdkAuthStateListener(): Unit = runBlocking {
        val seen = mutableListOf<AuthSession>()
        val collector = scope.launch { repository.session.collect { seen += it } }
        withTimeout(TIMEOUT_MS) {
            while (seen.none { it != AuthSession.Unresolved }) kotlinx.coroutines.yield()
        }
        val beforeCancellation = seen.size

        collector.cancelAndJoin()

        // A real auth-state change that the released listener must not observe.
        assertTrue(repository.signUp(uniqueEmail(), PASSWORD).isSuccess)
        withTimeout(TIMEOUT_MS) {
            while (auth.currentUser == null) kotlinx.coroutines.yield()
        }
        assertEquals(
            beforeCancellation,
            seen.size,
            "a released listener must not keep pushing auth-state changes",
        )
        Log.i("FluxItAuthTest", "listener teardown verified after $beforeCancellation emissions")
    }

    private suspend fun AndroidAuthRepository.awaitResolvedSession(): AuthSession =
        withTimeout(TIMEOUT_MS) { session.first { it != AuthSession.Unresolved } }

    private fun uniqueEmail(): String = "fb102-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    private companion object {
        const val APP_NAME = "fb102-instrumented-test"
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "fb102-emulator-only"
        const val TIMEOUT_MS = 20_000L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per [FirebaseAuth] instance. */
        var emulatorConfigured = false
    }
}
