@file:JsModule("./firebase-bridge.mjs")

package com.fluxit.firebase

/**
 * Kotlin view of `wasmJsMain/resources/firebase-bridge.mjs`, the only code that touches
 * the Firebase JS SDK. Internal to `wasmJsMain`; adapters reach it through Kotlin
 * interfaces (for example `WebAuthBridge`) so their logic is testable without Firebase.
 */

internal external interface JsAuthUser : JsAny {
    val uid: String
    val email: String?
    val emailVerified: Boolean
}

internal external interface JsBridgeError : JsAny {
    val code: String
    val message: String
}

internal external fun initializeFirebase(
    apiKey: String,
    authDomain: String,
    projectId: String,
    storageBucket: String,
    messagingSenderId: String,
    appId: String,
    emulatorHost: String?,
    authPort: Int,
)

internal external fun authCurrentUser(): JsAuthUser?

internal external fun authStateReady(done: () -> Unit)

internal external fun authAddStateListener(listener: (JsAuthUser?) -> Unit): JsAny

internal external fun authRemoveStateListener(handle: JsAny)

internal external fun authReloadCurrentUser(done: (JsBridgeError?) -> Unit)

internal external fun authSignUp(email: String, password: String, done: (JsBridgeError?) -> Unit)

internal external fun authSignIn(email: String, password: String, done: (JsBridgeError?) -> Unit)

internal external fun authSendPasswordResetEmail(email: String, done: (JsBridgeError?) -> Unit)

internal external fun authSignOut(done: (JsBridgeError?) -> Unit)

internal external fun clearSessionData(done: (JsBridgeError?) -> Unit)
