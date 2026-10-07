package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthUser
import platform.Foundation.NSError

/** Builds a Foundation error without touching any Firebase symbol. */
internal fun authError(code: Long, domain: String = FIREBASE_AUTH_ERROR_DOMAIN): NSError =
    NSError.errorWithDomain(domain = domain, code = code, userInfo = null)

/**
 * Double for [IosAuthBridge] that records listener registration/removal.
 *
 * The recording is the point: it is what makes ("cancelling the collector
 * releases the underlying listener") an assertion instead of a promise. A
 * `MutableStateFlow`-backed fake such as the one in `commonTest` cannot express it,
 * because it has no listener to release. This is the iOS counterpart of the
 * `RecordingAuthGateway`.
 */
internal class RecordingAuthBridge(
    private var user: AuthUser? = null,
) : IosAuthBridge {

    var addCount: Int = 0
        private set
    var removeCount: Int = 0
        private set

    /** Listeners currently registered; assertions read its size directly. */
    val liveListeners: MutableList<(AuthUser?) -> Unit> = mutableListOf()

    var reloadFailure: NSError? = null
    var signUpFailure: NSError? = null
    var signInFailure: NSError? = null
    var passwordResetFailure: NSError? = null
    var signOutFailure: NSError? = null

    val passwordResetsSent: MutableList<String> = mutableListOf()
    var signOutCount: Int = 0
        private set

    /** Set to have the bridge invoke its completion handler twice, as a buggy Swift impl could. */
    var invokeCompletionsTwice: Boolean = false

    override fun currentUser(): AuthUser? = user

    override fun addAuthStateListener(listener: (AuthUser?) -> Unit): IosAuthListenerHandle {
        addCount++
        liveListeners += listener
        return object : IosAuthListenerHandle {
            private var removed = false
            override fun remove() {
                if (removed) return
                removed = true
                removeCount++
                liveListeners -= listener
            }
        }
    }

    override fun reloadCurrentUser(completion: (NSError?) -> Unit) = finish(completion, reloadFailure)

    override fun signUp(email: String, password: String, completion: (NSError?) -> Unit) {
        if (signUpFailure == null) {
            emitUser(AuthUser(uid = "uid-$email", email = email, isEmailVerified = false))
        }
        finish(completion, signUpFailure)
    }

    override fun signIn(email: String, password: String, completion: (NSError?) -> Unit) {
        if (signInFailure == null) {
            emitUser(AuthUser(uid = "uid-$email", email = email, isEmailVerified = true))
        }
        finish(completion, signInFailure)
    }

    override fun sendPasswordResetEmail(email: String, completion: (NSError?) -> Unit) {
        if (passwordResetFailure == null) passwordResetsSent += email
        finish(completion, passwordResetFailure)
    }

    override fun signOut(completion: (NSError?) -> Unit) {
        if (signOutFailure == null) {
            signOutCount++
            emitUser(null)
        }
        finish(completion, signOutFailure)
    }

    /** Simulates the SDK reporting an auth-state change to every live listener. */
    fun emitUser(next: AuthUser?) {
        user = next
        liveListeners.toList().forEach { it(next) }
    }

    private fun finish(completion: (NSError?) -> Unit, error: NSError?) {
        completion(error)
        if (invokeCompletionsTwice) completion(error)
    }
}

/** [AuthDiagnostics] that keeps every report so tests can assert what was logged. */
internal class RecordingDiagnostics : AuthDiagnostics {

    data class Entry(
        val operation: String,
        val domain: String,
        val code: Long,
        val mapped: AuthError,
        val recognised: Boolean,
    )

    val entries: MutableList<Entry> = mutableListOf()

    override fun onSdkFailure(
        operation: String,
        error: NSError,
        mapped: AuthError,
        recognised: Boolean,
    ) {
        entries += Entry(operation, error.domain.orEmpty(), error.code, mapped, recognised)
    }
}
