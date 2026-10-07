package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthUser
import com.fluxit.firebase.JsAuthUser
import com.fluxit.firebase.WebBridgeError
import com.fluxit.firebase.WebFirebase
import com.fluxit.firebase.authAddStateListener
import com.fluxit.firebase.authCurrentUser
import com.fluxit.firebase.authReloadCurrentUser
import com.fluxit.firebase.authRemoveStateListener
import com.fluxit.firebase.authSendPasswordResetEmail
import com.fluxit.firebase.authSignIn
import com.fluxit.firebase.authSignOut
import com.fluxit.firebase.authSignUp
import com.fluxit.firebase.authStateReady
import com.fluxit.firebase.toWebBridgeError

/** Releases a listener added through [WebAuthBridge.addAuthStateListener]. */
internal interface WebAuthListenerHandle {

    /** Idempotent: calling it more than once must not remove a later listener. */
    fun remove()
}

/**
 * The seam through which [WebAuthRepository] reaches Firebase Auth: the web counterpart
 * of `IosAuthBridge`, with the same shape so the ported repository logic stays identical.
 *
 * Plain values only: users cross as the shared [AuthUser], failures as [WebBridgeError].
 * Every completion handler is invoked exactly once; `null` means success.
 */
internal interface WebAuthBridge {

    /** Calls [done] once the persisted credential has been loaded; [currentUser] is unreliable before. */
    fun awaitPersistence(done: () -> Unit)

    /** The locally persisted user, or `null`. Must not hit the network. */
    fun currentUser(): AuthUser?

    /** Registers an auth-state listener; the JS SDK invokes it once with the current state. */
    fun addAuthStateListener(listener: (AuthUser?) -> Unit): WebAuthListenerHandle

    /** Re-reads the current user from the server. */
    fun reloadCurrentUser(completion: (WebBridgeError?) -> Unit)

    fun signUp(email: String, password: String, completion: (WebBridgeError?) -> Unit)

    fun signIn(email: String, password: String, completion: (WebBridgeError?) -> Unit)

    fun sendPasswordResetEmail(email: String, completion: (WebBridgeError?) -> Unit)

    /** Signs out and clears Auth's persisted credential. Data clients are cleared by `WebSessionCleanup`. */
    fun signOut(completion: (WebBridgeError?) -> Unit)
}

/** [WebAuthBridge] over `firebase-bridge.mjs`. Starts Firebase on first use. */
internal object JsWebAuthBridge : WebAuthBridge {

    override fun awaitPersistence(done: () -> Unit) {
        WebFirebase.ensureStarted()
        authStateReady(done)
    }

    override fun currentUser(): AuthUser? {
        WebFirebase.ensureStarted()
        return authCurrentUser()?.toAuthUser()
    }

    override fun addAuthStateListener(listener: (AuthUser?) -> Unit): WebAuthListenerHandle {
        WebFirebase.ensureStarted()
        val handle = authAddStateListener { user -> listener(user?.toAuthUser()) }
        return object : WebAuthListenerHandle {
            override fun remove() = authRemoveStateListener(handle)
        }
    }

    override fun reloadCurrentUser(completion: (WebBridgeError?) -> Unit) {
        WebFirebase.ensureStarted()
        authReloadCurrentUser { completion(it?.toWebBridgeError()) }
    }

    override fun signUp(email: String, password: String, completion: (WebBridgeError?) -> Unit) {
        WebFirebase.ensureStarted()
        authSignUp(email, password) { completion(it?.toWebBridgeError()) }
    }

    override fun signIn(email: String, password: String, completion: (WebBridgeError?) -> Unit) {
        WebFirebase.ensureStarted()
        authSignIn(email, password) { completion(it?.toWebBridgeError()) }
    }

    override fun sendPasswordResetEmail(email: String, completion: (WebBridgeError?) -> Unit) {
        WebFirebase.ensureStarted()
        authSendPasswordResetEmail(email) { completion(it?.toWebBridgeError()) }
    }

    override fun signOut(completion: (WebBridgeError?) -> Unit) {
        WebFirebase.ensureStarted()
        authSignOut { completion(it?.toWebBridgeError()) }
    }

    private fun JsAuthUser.toAuthUser() = AuthUser(uid = uid, email = email, isEmailVerified = emailVerified)
}
