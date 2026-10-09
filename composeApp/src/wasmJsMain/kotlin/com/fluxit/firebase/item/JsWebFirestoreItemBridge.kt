package com.fluxit.firebase.item

import com.fluxit.data.remote.FirebaseValue
import com.fluxit.firebase.JsWireField
import com.fluxit.firebase.WebBridgeError
import com.fluxit.firebase.WebFirebase
import com.fluxit.firebase.WebFirestoreCodec
import com.fluxit.firebase.fsAddItem
import com.fluxit.firebase.fsClearCompletedChunk
import com.fluxit.firebase.fsMutateItemWithCounters
import com.fluxit.firebase.fsObserveCollection
import com.fluxit.firebase.fsObserveDocument
import com.fluxit.firebase.fsUpdateFields
import com.fluxit.firebase.list.WebFirestoreListenerHandle
import com.fluxit.firebase.list.asListenerHandle
import com.fluxit.firebase.list.toDocument
import com.fluxit.firebase.list.toDocuments
import com.fluxit.firebase.toWebBridgeError

/** [WebFirestoreItemBridge] over `firebase-bridge.mjs`. Starts Firebase on first use. */
internal object JsWebFirestoreItemBridge : WebFirestoreItemBridge {

    override fun observeItems(
        uid: String,
        listId: String,
        onSnapshot: (List<WebFirestoreItemDocument>) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        WebFirebase.ensureStarted()
        return fsObserveCollection(
            uid, listId, false,
            { documents, _, _ -> onSnapshot(documents.toDocuments()) },
            { onError(it.toWebBridgeError()) },
        ).asListenerHandle()
    }

    override fun observeItemsSnapshot(
        uid: String,
        listId: String,
        onSnapshot: (WebFirestoreItemSnapshot) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        WebFirebase.ensureStarted()
        return fsObserveCollection(
            uid, listId, true,
            { documents, fromCache, pendingWrites ->
                onSnapshot(WebFirestoreItemSnapshot(documents.toDocuments(), fromCache, pendingWrites))
            },
            { onError(it.toWebBridgeError()) },
        ).asListenerHandle()
    }

    override fun observeItem(
        uid: String,
        listId: String,
        itemId: String,
        onSnapshot: (WebFirestoreItemDocument?) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        WebFirebase.ensureStarted()
        return fsObserveDocument(
            uid, listId, itemId,
            { document -> onSnapshot(document?.toDocument()) },
            { onError(it.toWebBridgeError()) },
        ).asListenerHandle()
    }

    override fun addItem(
        uid: String,
        listId: String,
        fields: Map<String, FirebaseValue>,
        completion: (String?, WebBridgeError?) -> Unit,
    ) {
        WebFirebase.ensureStarted()
        fsAddItem(uid, listId, WebFirestoreCodec.encode(fields)) { id, error -> completion(id, error?.toWebBridgeError()) }
    }

    override fun updateItemFields(
        uid: String,
        listId: String,
        itemId: String,
        fields: Map<String, FirebaseValue>,
        completion: (WebBridgeError?) -> Unit,
    ) {
        WebFirebase.ensureStarted()
        fsUpdateFields(uid, listId, itemId, WebFirestoreCodec.encode(fields)) { completion(it?.toWebBridgeError()) }
    }

    override fun mutateItemWithCounters(
        uid: String,
        listId: String,
        itemId: String,
        decide: (fields: Map<String, FirebaseValue>?) -> ItemCounterOutcome,
        completion: (WebBridgeError?) -> Unit,
    ) {
        WebFirebase.ensureStarted()
        fsMutateItemWithCounters(
            uid, listId, itemId,
            { wire -> encodeOutcome(decide(wire?.let(WebFirestoreCodec::decode))) },
            { completion(it?.toWebBridgeError()) },
        )
    }

    override fun clearCompletedChunk(
        uid: String,
        listId: String,
        chunkSize: Int,
        itemPatch: Map<String, FirebaseValue>,
        completion: (Int, WebBridgeError?) -> Unit,
    ) {
        WebFirebase.ensureStarted()
        fsClearCompletedChunk(uid, listId, chunkSize, WebFirestoreCodec.encode(itemPatch)) { count, error ->
            completion(count, error?.toWebBridgeError())
        }
    }
}

/** `{ kind, fields, delta }` as `fsMutateItemWithCounters` expects it. */
internal fun encodeOutcome(outcome: ItemCounterOutcome): JsAny = when (outcome) {
    ItemCounterOutcome.NoOp -> counterOutcome("noop", null, null)
    is ItemCounterOutcome.ApplyPatch -> counterOutcome(
        "patch",
        WebFirestoreCodec.encode(outcome.itemFields),
        WebFirestoreCodec.encodeIncrements(outcome.counterDelta),
    )
    is ItemCounterOutcome.HardDelete -> counterOutcome("delete", null, WebFirestoreCodec.encodeIncrements(outcome.counterDelta))
}

@Suppress("UNUSED_PARAMETER")
private fun counterOutcome(kind: String, fields: JsArray<JsWireField>?, delta: JsArray<JsWireField>?): JsAny =
    js("({ kind: kind, fields: fields, delta: delta })")
