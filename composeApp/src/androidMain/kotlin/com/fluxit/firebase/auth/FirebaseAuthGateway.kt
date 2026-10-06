package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthUser
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Removes a previously added auth-state listener.
 *
 * Returned by [FirebaseAuthGateway.addAuthStateListener] so the caller never has to
 * hold on to the SDK listener object itself. [AndroidAuthRepository] calls [remove]
 * from `awaitClose`, which is what makes the "cancelling the collector releases the
 * underlying listener" clause of `AuthRepository.session` real (FB-101-NB3).
 */
internal fun interface AuthStateRegistration {
    fun remove()
}

/**
 * The only seam through which FB-102's adapter touches the Firebase Auth SDK.
 *
 * It exists so [AndroidAuthRepository]'s own logic - the session state machine, the
 * `callbackFlow` listener lifecycle, and the error mapping - is exercisable by JVM unit
 * tests with a recording double, without a device, a provider, or a network. It carries
 * no Firebase types in its signatures on purpose: everything crossing it is either a
 * plain value or a [Throwable] that only the error mapper is allowed to interpret.
 */
internal interface FirebaseAuthGateway {

    /** The locally persisted user, or `null`. Does not hit the network. */
    fun currentUser(): AuthUser?

    /**
     * Registers a listener for auth-state changes. The Firebase SDK invokes the
     * listener once immediately with the current state, which is what resolves the
     * session for a collector that never calls `restoreSession`.
     */
    fun addAuthStateListener(listener: (AuthUser?) -> Unit): AuthStateRegistration

    /** Re-reads the current user from the server; throws if that cannot be completed. */
    suspend fun reloadCurrentUser()

    suspend fun signUp(email: String, password: String)

    suspend fun signIn(email: String, password: String)

    suspend fun sendPasswordResetEmail(email: String)

    /**
     * Signs out and clears Firebase Auth's own persisted credential for this app
     * (DEC-003a, Auth's share of it). Firestore/Storage cache clearing is deliberately
     * not done here - see [AndroidAuthRepository.signOut].
     */
    fun signOut()
}

/** [FirebaseAuthGateway] backed by the official Firebase Android Auth SDK. */
internal class FirebaseSdkAuthGateway(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : FirebaseAuthGateway {

    override fun currentUser(): AuthUser? = auth.currentUser?.toAuthUser()

    override fun addAuthStateListener(listener: (AuthUser?) -> Unit): AuthStateRegistration {
        val sdkListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            listener(firebaseAuth.currentUser?.toAuthUser())
        }
        auth.addAuthStateListener(sdkListener)
        return AuthStateRegistration { auth.removeAuthStateListener(sdkListener) }
    }

    override suspend fun reloadCurrentUser() {
        val user = auth.currentUser ?: return
        user.reload().awaitCompletion()
    }

    override suspend fun signUp(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password).awaitCompletion()
    }

    override suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).awaitCompletion()
    }

    override suspend fun sendPasswordResetEmail(email: String) {
        auth.sendPasswordResetEmail(email).awaitCompletion()
    }

    override fun signOut() {
        auth.signOut()
    }
}

private fun FirebaseUser.toAuthUser(): AuthUser =
    AuthUser(uid = uid, email = email, isEmailVerified = isEmailVerified)

/**
 * Suspends until the task completes, rethrowing its exception so the single error
 * mapper - and nothing else - decides what the failure means.
 */
private suspend fun Task<*>.awaitCompletion() {
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val exception = task.exception
            when {
                exception != null -> continuation.resumeWithException(exception)
                task.isCanceled -> continuation.cancel(CancellationException("Firebase task cancelled"))
                else -> continuation.resume(Unit)
            }
        }
    }
}
