package com.fluxit.firebase.list

import com.fluxit.data.remote.FirebaseValue
import com.fluxit.firebase.WebBridgeError

/** Releases a Firestore snapshot listener. Idempotent. Web counterpart of `IosFirestoreListenerHandle`. */
internal interface WebFirestoreListenerHandle {
    fun remove()
}

/**
 * One raw Firestore document in the shared [FirebaseValue] wire format. A field absent
 * from the document is absent from [fields]; an explicit null is [FirebaseValue.Null].
 * Same contract as `IosFirestoreListDocument`.
 */
internal data class WebFirestoreListDocument(
    val id: String,
    val fields: Map<String, FirebaseValue>,
)

/** [WebFirestoreListBridge.observeListSummariesSnapshot]'s payload, with the real snapshot metadata. */
internal data class WebFirestoreListSnapshot(
    val documents: List<WebFirestoreListDocument>,
    val isFromCache: Boolean,
    val hasPendingWrites: Boolean,
)

/**
 * The seam through which [WebFirebaseListRepository] reaches `users/{uid}/lists`. Same
 * methods as `IosFirestoreListBridge`, so the repository logic ports unchanged; the
 * implementation is [JsWebFirestoreListBridge] over `firebase-bridge.mjs`.
 *
 * Writes take `Map<String, FirebaseValue>`: [createList] writes a new document's full
 * field set, [updateListFields] touches only the keys present (field-level
 * last-write-wins). Tombstone filtering and ordering stay in the repository's mapper.
 * Every completion is invoked exactly once; `null` error means success.
 */
internal interface WebFirestoreListBridge {

    /** Listener on `users/{uid}/lists`; the SDK reports the current contents first. */
    fun observeListSummaries(
        uid: String,
        onSnapshot: (List<WebFirestoreListDocument>) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle

    /**
     * Same query as [observeListSummaries] but a separate listener with
     * `includeMetadataChanges: true`, so metadata-only transitions (a pending write
     * acknowledged) are reported without changing [observeListSummaries]' behaviour.
     */
    fun observeListSummariesSnapshot(
        uid: String,
        onSnapshot: (WebFirestoreListSnapshot) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle

    /** Listener on one list document; `null` when it does not exist. */
    fun observeList(
        uid: String,
        listId: String,
        onSnapshot: (WebFirestoreListDocument?) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle

    /** Creates a new auto-ID list document (whole-document write) and reports its ID. */
    fun createList(
        uid: String,
        fields: Map<String, FirebaseValue>,
        completion: (String?, WebBridgeError?) -> Unit,
    )

    /** Field-scoped `updateDoc`; never a whole-document write. */
    fun updateListFields(
        uid: String,
        listId: String,
        fields: Map<String, FirebaseValue>,
        completion: (WebBridgeError?) -> Unit,
    )
}
