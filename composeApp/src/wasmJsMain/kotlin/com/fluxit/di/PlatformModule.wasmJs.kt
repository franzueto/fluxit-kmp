package com.fluxit.di

import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Phase 0 spike bindings: only what the signed-out gate and the auth screen resolve.
 * The Firebase-backed web adapters replace [SpikeAuthRepository] in Phase 2 and add the
 * list, item and photo bindings in Phases 3–4 (docs/web-app/PROGRESS.md).
 */
actual fun platformModule(): Module = module {
    single<AuthRepository> { SpikeAuthRepository() }
}

/** Resolves to [AuthSession.SignedOut] so the real login screen renders; every operation fails. */
private class SpikeAuthRepository : AuthRepository {
    override val session: StateFlow<AuthSession> = MutableStateFlow(AuthSession.SignedOut)
    override suspend fun restoreSession() = Unit
    override suspend fun signUp(email: String, password: String) = unavailable
    override suspend fun signIn(email: String, password: String) = unavailable
    override suspend fun sendPasswordResetEmail(email: String) = unavailable
    override suspend fun signOut() = AuthResult.Success

    private val unavailable = AuthResult.Failure(AuthError.Unknown)
}
