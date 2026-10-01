package com.fluxit.firebase.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthSession
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * FB-102 wiring check: the real application's Koin graph resolves [AuthRepository] to
 * the Android adapter.
 *
 * This runs inside `FluxItApplication`, i.e. against the Koin graph the shipped app
 * actually builds, so it fails if `platformModule()` ever stops binding the adapter or
 * if the binding cannot be constructed. It performs no authentication call: resolving
 * the binding touches only `FirebaseAuth.getInstance()` on the already-initialised
 * default app, which is local and does not reach any backend.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAuthDiInstrumentedTest {

    @Test
    fun koinResolvesTheAndroidAuthAdapter() {
        val repository = GlobalContext.get().get<AuthRepository>()

        assertIs<AndroidAuthRepository>(repository)
    }

    @Test
    fun aFreshAdapterStartsUnresolvedEvenIfTheApplicationGraphWasAlreadyResolved() = runBlocking {
        // Other instrumented UI tests may already have restored the application's
        // singleton. Initial-state semantics belong to a newly created adapter.
        val repository = AndroidAuthRepository()

        val first = withTimeout(5_000) { repository.session.first() }

        assertEquals(AuthSession.Unresolved, first)
    }
}
