@file:JsModule("./firebase-bridge.mjs")

package com.fluxit.firebase

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array

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
    firestorePort: Int,
    storagePort: Int,
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

/** One Firestore field in the bridge's wire format; see [WebFirestoreCodec]. */
internal external interface JsWireField : JsAny {
    val key: String
    val type: String
    val text: String?
    val bool: Boolean
    val number: Double
}

internal external interface JsFirestoreDocument : JsAny {
    val id: String
    val fields: JsArray<JsWireField>
}

internal external fun fsObserveCollection(
    uid: String,
    listId: String?,
    includeMetadataChanges: Boolean,
    onSnapshotDocs: (JsArray<JsFirestoreDocument>, Boolean, Boolean) -> Unit,
    onError: (JsBridgeError) -> Unit,
): JsAny

internal external fun fsObserveDocument(
    uid: String,
    listId: String,
    itemId: String?,
    onSnapshotDoc: (JsFirestoreDocument?) -> Unit,
    onError: (JsBridgeError) -> Unit,
): JsAny

internal external fun fsRemoveListener(handle: JsAny)

internal external fun fsCreateList(uid: String, wire: JsArray<JsWireField>, done: (String?, JsBridgeError?) -> Unit)

internal external fun fsUpdateFields(
    uid: String,
    listId: String,
    itemId: String?,
    wire: JsArray<JsWireField>,
    done: (JsBridgeError?) -> Unit,
)

internal external fun fsAddItem(uid: String, listId: String, wire: JsArray<JsWireField>, done: (String?, JsBridgeError?) -> Unit)

internal external fun fsMutateItemWithCounters(
    uid: String,
    listId: String,
    itemId: String,
    decide: (JsArray<JsWireField>?) -> JsAny,
    done: (JsBridgeError?) -> Unit,
)

internal external fun fsClearCompletedChunk(
    uid: String,
    listId: String,
    chunkSize: Int,
    patchWire: JsArray<JsWireField>,
    done: (Int, JsBridgeError?) -> Unit,
)

internal external fun storageUpload(photoRef: String, bytes: Uint8Array, mimeType: String, done: (JsBridgeError?) -> Unit)

internal external fun storageDownload(photoRef: String, maxSize: Double, done: (ArrayBuffer?, JsBridgeError?) -> Unit)

internal external fun storageDelete(photoRef: String, done: (JsBridgeError?) -> Unit)
