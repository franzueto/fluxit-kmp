package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthUser

/**
 * Double for [FirebaseAuthGateway] that records listener registration/removal.
 *
 * The recording is the point: it is what makes FB-101-NB3 ("cancelling the collector
 * releases the underlying listener") an assertion instead of a promise. A
 * `MutableStateFlow`-backed fake such as the one in `commonTest` cannot express it,
 * because it has no listener to release.
 */
internal class RecordingAuthGateway(
    private var user: AuthUser? = null,
) : FirebaseAuthGateway {

    var addCount: Int = 0
        private set
    var removeCount: Int = 0
        private set

    /** Listeners currently registered; assertions read its size directly. */
    val liveListeners: MutableList<(AuthUser?) -> Unit> = mutableListOf()

    var reloadFailure: Throwable? = null
    var signUpFailure: Throwable? = null
    var signInFailure: Throwable? = null
    var passwordResetFailure: Throwable? = null
    var signOutFailure: Throwable? = null

    val passwordResetsSent: MutableList<String> = mutableListOf()
    var signOutCount: Int = 0
        private set

    override fun currentUser(): AuthUser? = user

    override fun addAuthStateListener(listener: (AuthUser?) -> Unit): AuthStateRegistration {
        addCount++
        liveListeners += listener
        return AuthStateRegistration {
            removeCount++
            liveListeners -= listener
        }
    }

    override suspend fun reloadCurrentUser() {
        reloadFailure?.let { throw it }
    }

    override suspend fun signUp(email: String, password: String) {
        signUpFailure?.let { throw it }
        emitUser(AuthUser(uid = "uid-$email", email = email, isEmailVerified = false))
    }

    override suspend fun signIn(email: String, password: String) {
        signInFailure?.let { throw it }
        emitUser(AuthUser(uid = "uid-$email", email = email, isEmailVerified = true))
    }

    override suspend fun sendPasswordResetEmail(email: String) {
        passwordResetFailure?.let { throw it }
        passwordResetsSent += email
    }

    override fun signOut() {
        signOutFailure?.let { throw it }
        signOutCount++
        emitUser(null)
    }

    /** Simulates the SDK reporting an auth-state change to every live listener. */
    fun emitUser(next: AuthUser?) {
        user = next
        liveListeners.toList().forEach { it(next) }
    }
}

/** [AuthDiagnostics] that keeps every report so tests can assert what was logged. */
internal class RecordingDiagnostics : AuthDiagnostics {

    data class Entry(
        val operation: String,
        val throwable: Throwable,
        val errorCode: String?,
        val mapped: AuthError,
        val recognised: Boolean,
    )

    val entries: MutableList<Entry> = mutableListOf()

    override fun onSdkFailure(
        operation: String,
        throwable: Throwable,
        errorCode: String?,
        mapped: AuthError,
        recognised: Boolean,
    ) {
        entries += Entry(operation, throwable, errorCode, mapped, recognised)
    }
}
