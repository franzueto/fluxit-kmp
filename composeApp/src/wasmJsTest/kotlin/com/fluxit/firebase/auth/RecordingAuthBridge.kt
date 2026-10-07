package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthError
import com.fluxit.domain.auth.AuthUser
import com.fluxit.firebase.WebBridgeError

internal fun authError(code: String): WebBridgeError = WebBridgeError(code, "Firebase: Error ($code).")

/** Double for [WebAuthBridge] that records listener registration and removal. Port of the iOS double. */
internal class RecordingAuthBridge(
    private var user: AuthUser? = null,
) : WebAuthBridge {

    var addCount: Int = 0
        private set
    var removeCount: Int = 0
        private set

    val liveListeners: MutableList<(AuthUser?) -> Unit> = mutableListOf()

    var reloadFailure: WebBridgeError? = null
    var signUpFailure: WebBridgeError? = null
    var signInFailure: WebBridgeError? = null
    var passwordResetFailure: WebBridgeError? = null
    var signOutFailure: WebBridgeError? = null

    val passwordResetsSent: MutableList<String> = mutableListOf()
    var signOutCount: Int = 0
        private set

    /** When false, the persisted credential has not loaded yet: [currentUser] reads null. */
    var persistenceLoaded: Boolean = true
    private val persistenceWaiters = mutableListOf<() -> Unit>()

    var invokeCompletionsTwice: Boolean = false

    override fun awaitPersistence(done: () -> Unit) {
        if (persistenceLoaded) done() else persistenceWaiters += done
    }

    /** Simulates the JS SDK finishing loading the persisted credential. */
    fun finishLoadingPersistence() {
        persistenceLoaded = true
        persistenceWaiters.toList().also { persistenceWaiters.clear() }.forEach { it() }
    }

    override fun currentUser(): AuthUser? = if (persistenceLoaded) user else null

    override fun addAuthStateListener(listener: (AuthUser?) -> Unit): WebAuthListenerHandle {
        addCount++
        liveListeners += listener
        return object : WebAuthListenerHandle {
            private var removed = false
            override fun remove() {
                if (removed) return
                removed = true
                removeCount++
                liveListeners -= listener
            }
        }
    }

    override fun reloadCurrentUser(completion: (WebBridgeError?) -> Unit) = finish(completion, reloadFailure)

    override fun signUp(email: String, password: String, completion: (WebBridgeError?) -> Unit) {
        if (signUpFailure == null) emitUser(AuthUser(uid = "uid-$email", email = email))
        finish(completion, signUpFailure)
    }

    override fun signIn(email: String, password: String, completion: (WebBridgeError?) -> Unit) {
        if (signInFailure == null) emitUser(AuthUser(uid = "uid-$email", email = email, isEmailVerified = true))
        finish(completion, signInFailure)
    }

    override fun sendPasswordResetEmail(email: String, completion: (WebBridgeError?) -> Unit) {
        if (passwordResetFailure == null) passwordResetsSent += email
        finish(completion, passwordResetFailure)
    }

    override fun signOut(completion: (WebBridgeError?) -> Unit) {
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

    private fun finish(completion: (WebBridgeError?) -> Unit, error: WebBridgeError?) {
        completion(error)
        if (invokeCompletionsTwice) completion(error)
    }
}

/** [AuthDiagnostics] that keeps every report. */
internal class RecordingDiagnostics : AuthDiagnostics {

    data class Entry(val operation: String, val code: String, val mapped: AuthError, val recognised: Boolean)

    val entries: MutableList<Entry> = mutableListOf()

    override fun onSdkFailure(operation: String, error: WebBridgeError, mapped: AuthError, recognised: Boolean) {
        entries += Entry(operation, error.code, mapped, recognised)
    }
}
