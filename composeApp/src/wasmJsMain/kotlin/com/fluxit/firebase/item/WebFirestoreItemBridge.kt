package com.fluxit.firebase.item

import com.fluxit.data.remote.FirebaseValue
import com.fluxit.firebase.WebBridgeError
import com.fluxit.firebase.list.WebFirestoreListDocument
import com.fluxit.firebase.list.WebFirestoreListenerHandle

/** Items use the same neutral document shape as lists. */
internal typealias WebFirestoreItemDocument = WebFirestoreListDocument

/** [WebFirestoreItemBridge.observeItemsSnapshot]'s payload, with the real snapshot metadata. */
internal data class WebFirestoreItemSnapshot(
    val documents: List<WebFirestoreItemDocument>,
    val isFromCache: Boolean,
    val hasPendingWrites: Boolean,
)

/**
 * What a counter-affecting mutation writes, decided in Kotlin from the item fields the
 * transaction read. Same type as on iOS: the policy stays in [WebFirebaseItemRepository],
 * the bridge only opens the transaction and applies the outcome.
 */
internal sealed interface ItemCounterOutcome {

    /** The item does not exist: write nothing. */
    data object NoOp : ItemCounterOutcome

    /** Field-scoped item update plus `increment()`s on the parent list's counters (may be empty). */
    data class ApplyPatch(
        val itemFields: Map<String, FirebaseValue>,
        val counterDelta: Map<String, FirebaseValue.Number>,
    ) : ItemCounterOutcome

    /** Hard-deletes the item plus `increment()`s on the parent list's counters (may be empty). */
    data class HardDelete(val counterDelta: Map<String, FirebaseValue.Number>) : ItemCounterOutcome
}

/**
 * The seam through which [WebFirebaseItemRepository] reaches
 * `users/{uid}/lists/{listId}/items`. Same methods as `IosFirestoreItemBridge`; the
 * implementation is [JsWebFirestoreItemBridge] over `firebase-bridge.mjs`. Every
 * completion is invoked exactly once; `null` error means success.
 */
internal interface WebFirestoreItemBridge {

    fun observeItems(
        uid: String,
        listId: String,
        onSnapshot: (List<WebFirestoreItemDocument>) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle

    /** Separate `includeMetadataChanges: true` listener; see the list bridge's counterpart. */
    fun observeItemsSnapshot(
        uid: String,
        listId: String,
        onSnapshot: (WebFirestoreItemSnapshot) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle

    fun observeItem(
        uid: String,
        listId: String,
        itemId: String,
        onSnapshot: (WebFirestoreItemDocument?) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle

    /** One batch: create the auto-ID item (whole-document write) and `increment(1)` the list's `totalItems`. */
    fun addItem(
        uid: String,
        listId: String,
        fields: Map<String, FirebaseValue>,
        completion: (String?, WebBridgeError?) -> Unit,
    )

    /** Field-scoped item update with no counter effect. */
    fun updateItemFields(
        uid: String,
        listId: String,
        itemId: String,
        fields: Map<String, FirebaseValue>,
        completion: (WebBridgeError?) -> Unit,
    )

    /**
     * Runs a transaction that reads the item, calls [decide] with its fields (`null` when
     * missing) and applies the returned [ItemCounterOutcome]. [decide] may run more than
     * once (SDK contention retries), so it must be pure. Like iOS, a `permission-denied`
     * after a read is retried (up to 3 times) only when a server re-read shows the item
     * changed, since a stale delta can fail the counter Rules.
     */
    fun mutateItemWithCounters(
        uid: String,
        listId: String,
        itemId: String,
        decide: (fields: Map<String, FirebaseValue>?) -> ItemCounterOutcome,
        completion: (WebBridgeError?) -> Unit,
    )

    /**
     * Tombstones up to [chunkSize] active completed items with [itemPatch] and decrements
     * both list counters by the count, in one batch. Reports how many were cleared; `0`
     * means nothing was left.
     */
    fun clearCompletedChunk(
        uid: String,
        listId: String,
        chunkSize: Int,
        itemPatch: Map<String, FirebaseValue>,
        completion: (Int, WebBridgeError?) -> Unit,
    )
}
