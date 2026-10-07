package com.fluxit.firebase.auth

import com.fluxit.domain.auth.AuthUser
import platform.Foundation.NSError

/**
 * Releases a previously added auth-state listener.
 *
 * The Swift side returns one of these from [IosAuthBridge.addAuthStateListener] so the
 * Kotlin side never has to hold the Firebase `AuthStateDidChangeListenerHandle` itself.
 * [IosAuthRepository] calls [remove] from `awaitClose`, which is what makes the
 * "cancelling the collector releases the underlying listener" clause of
 * `AuthRepository.session` real on iOS.
 */
interface IosAuthListenerHandle {

    /** Idempotent: calling it more than once must not remove a later listener. */
    fun remove()
}

/**
 * The Swift-implemented seam through which the iOS adapter reaches Firebase Auth.
 *
 * The `FirebaseAuth` SPM target is not cinterop-reachable from `iosMain`, so
 * every Firebase-touching line lives in Swift (`iosApp/iosApp/FirebaseAuthBridge.swift`)
 * and is bridged back across the framework boundary through this protocol. Kotlin
 * declares the contract, Swift implements it, and the app registers the implementation
 * into [IosAuthBridgeRegistry] during `FirebaseApp.configure()`.
 *
 * Nothing Firebase-specific crosses this boundary. Everything on it is either a plain
 * value, the shared [AuthUser], or an [NSError] - a Foundation type, not an SDK type -
 * which only [mapAuthFailure] is allowed to interpret. In particular no SDK code,
 * message or object ever reaches `commonMain`.
 *
 * Asynchronous operations are expressed as completion handlers rather than `suspend`
 * functions on purpose: a Kotlin `suspend` function cannot be implemented from Swift,
 * and `AuthRepository`'s KDoc already anticipates an iOS `actual` that "resumes on a
 * completion handler". [IosAuthRepository] is what turns them back into `suspend`.
 *
 * Every completion handler must be invoked exactly once, on any thread. `null` means
 * success.
 */
interface IosAuthBridge {

    /** The locally persisted user, or `null`. Must not hit the network. */
    fun currentUser(): AuthUser?

    /**
     * Registers a Firebase auth-state listener. The Apple SDK invokes a freshly added
     * listener once with the current state, which is what resolves the session for a
     * collector that never calls `restoreSession`.
     */
    fun addAuthStateListener(listener: (AuthUser?) -> Unit): IosAuthListenerHandle

    /** Re-reads the current user from the server. Reports failure through [NSError]. */
    fun reloadCurrentUser(completion: (NSError?) -> Unit)

    fun signUp(email: String, password: String, completion: (NSError?) -> Unit)

    fun signIn(email: String, password: String, completion: (NSError?) -> Unit)

    fun sendPasswordResetEmail(email: String, completion: (NSError?) -> Unit)

    /**
     * Signs out and clears Firebase Auth's own persisted credential for this app
     * (Auth's share of it). Firestore/Storage cache clearing is deliberately
     * not done here - see [IosAuthRepository.signOut].
     */
    fun signOut(completion: (NSError?) -> Unit)
}

/**
 * Hand-off point between the Swift app layer and the Kotlin framework.
 *
 * `FirebaseBootstrap.start()` registers the real bridge from
 * `AppDelegate.application(_:didFinishLaunchingWithOptions:)`, which runs strictly
 * before `ContentView` creates the Compose view controller that starts Koin
 * (`MainViewController.kt`). The Koin binding added by is a `single`, so the
 * bridge is only looked up when something first injects `AuthRepository` - which is
 * later still (adds the first consumer). therefore does not need to move
 * Koin's start point, and deliberately does not.
 */
object IosAuthBridgeRegistry {

    private var registered: IosAuthBridge? = null

    /** Called once from Swift, immediately after `FirebaseApp.configure()`. */
    fun register(bridge: IosAuthBridge) {
        registered = bridge
    }

    /** Test/diagnostic accessor; `null` before the app layer has registered. */
    fun bridgeOrNull(): IosAuthBridge? = registered

    internal fun requireBridge(): IosAuthBridge = checkNotNull(registered) {
        "No IosAuthBridge registered. FirebaseBootstrap.start() must run - and must " +
            "call IosAuthBridgeRegistry.register - before AuthRepository is resolved."
    }
}
