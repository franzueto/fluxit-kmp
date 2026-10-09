package com.fluxit.firebase.list

import com.fluxit.data.remote.FirebaseValue
import com.fluxit.firebase.JsFirestoreDocument
import com.fluxit.firebase.WebBridgeError
import com.fluxit.firebase.WebFirebase
import com.fluxit.firebase.WebFirestoreCodec
import com.fluxit.firebase.fsCreateList
import com.fluxit.firebase.fsObserveCollection
import com.fluxit.firebase.fsObserveDocument
import com.fluxit.firebase.fsRemoveListener
import com.fluxit.firebase.fsUpdateFields
import com.fluxit.firebase.toWebBridgeError

/** [WebFirestoreListBridge] over `firebase-bridge.mjs`. Starts Firebase on first use. */
internal object JsWebFirestoreListBridge : WebFirestoreListBridge {

    override fun observeListSummaries(
        uid: String,
        onSnapshot: (List<WebFirestoreListDocument>) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        WebFirebase.ensureStarted()
        return fsObserveCollection(
            uid, null, false,
            { documents, _, _ -> onSnapshot(documents.toDocuments()) },
            { onError(it.toWebBridgeError()) },
        ).asListenerHandle()
    }

    override fun observeListSummariesSnapshot(
        uid: String,
        onSnapshot: (WebFirestoreListSnapshot) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        WebFirebase.ensureStarted()
        return fsObserveCollection(
            uid, null, true,
            { documents, fromCache, pendingWrites ->
                onSnapshot(WebFirestoreListSnapshot(documents.toDocuments(), fromCache, pendingWrites))
            },
            { onError(it.toWebBridgeError()) },
        ).asListenerHandle()
    }

    override fun observeList(
        uid: String,
        listId: String,
        onSnapshot: (WebFirestoreListDocument?) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        WebFirebase.ensureStarted()
        return fsObserveDocument(
            uid, listId, null,
            { document -> onSnapshot(document?.toDocument()) },
            { onError(it.toWebBridgeError()) },
        ).asListenerHandle()
    }

    override fun createList(
        uid: String,
        fields: Map<String, FirebaseValue>,
        completion: (String?, WebBridgeError?) -> Unit,
    ) {
        WebFirebase.ensureStarted()
        fsCreateList(uid, WebFirestoreCodec.encode(fields)) { id, error -> completion(id, error?.toWebBridgeError()) }
    }

    override fun updateListFields(
        uid: String,
        listId: String,
        fields: Map<String, FirebaseValue>,
        completion: (WebBridgeError?) -> Unit,
    ) {
        WebFirebase.ensureStarted()
        fsUpdateFields(uid, listId, null, WebFirestoreCodec.encode(fields)) { completion(it?.toWebBridgeError()) }
    }
}

internal fun JsFirestoreDocument.toDocument(): WebFirestoreListDocument =
    WebFirestoreListDocument(id = id, fields = WebFirestoreCodec.decode(fields))

internal fun JsArray<JsFirestoreDocument>.toDocuments(): List<WebFirestoreListDocument> =
    (0 until length).mapNotNull { index -> get(index)?.toDocument() }

internal fun JsAny.asListenerHandle(): WebFirestoreListenerHandle {
    val handle = this
    return object : WebFirestoreListenerHandle {
        override fun remove() = fsRemoveListener(handle)
    }
}
