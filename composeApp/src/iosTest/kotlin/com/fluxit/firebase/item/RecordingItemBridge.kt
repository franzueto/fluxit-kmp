package com.fluxit.firebase.item

import com.fluxit.data.remote.FirebaseValue
import com.fluxit.firebase.list.IosFirestoreListDocument
import com.fluxit.firebase.list.IosFirestoreListenerHandle
import platform.Foundation.NSError

/**
 * Double for [IosFirestoreItemBridge] that records listener registration/removal and
 * every write, plus the exact [ItemCounterOutcome] decisions
 * [IosFirebaseItemRepository]'s `decide` callbacks compute against an
 * injectable current-item-state. The iOS counterpart of FB-204's Android chunk/behavior
 * tests and FB-203's `RecordingListBridge` (whose shape this deliberately mirrors).
 *
 * The recording is the point: it is what makes "cancelling the collector releases the
 * underlying listener" an assertion instead of a promise, what lets a test assert that
 * [addItem] used a whole-document write while [updateItemFields] only ever carried the
 * changed keys (`DEC-003d`/`DEC-003d-1`), and - the FB-205-specific addition -
 * [mutateItemWithCounters] lets a test drive `decide` against a chosen "current field
 * state" (or `null`, simulating a missing item) and inspect exactly which
 * [ItemCounterOutcome] the repository's policy computed, without a real Firestore
 * transaction anywhere in the loop.
 */
internal class RecordingItemBridge : IosFirestoreItemBridge {

    var itemsAddCount: Int = 0
        private set
    var itemsRemoveCount: Int = 0
        private set
    private var itemsListener: ((List<IosFirestoreItemDocument>) -> Unit)? = null
    private var itemsErrorListener: ((NSError) -> Unit)? = null

    var itemAddCount: Int = 0
        private set
    var itemRemoveCount: Int = 0
        private set
    private var itemListener: ((IosFirestoreItemDocument?) -> Unit)? = null
    private var itemErrorListener: ((NSError) -> Unit)? = null

    data class AddCall(val uid: String, val listId: String, val fields: Map<String, FirebaseValue>)
    data class UpdateCall(val uid: String, val listId: String, val itemId: String, val fields: Map<String, FirebaseValue>)
    data class CounterMutationCall(val uid: String, val listId: String, val itemId: String, val outcome: ItemCounterOutcome)
    data class ClearChunkCall(val uid: String, val listId: String, val chunkSize: Int)

    val addCalls: MutableList<AddCall> = mutableListOf()
    val updateCalls: MutableList<UpdateCall> = mutableListOf()
    val counterMutationCalls: MutableList<CounterMutationCall> = mutableListOf()
    val clearChunkCalls: MutableList<ClearChunkCall> = mutableListOf()

    var addFailure: NSError? = null
    var addedIdOverride: String? = null
    var updateFailure: NSError? = null
    var counterMutationFailure: NSError? = null

    /** What [mutateItemWithCounters] hands to `decide` as the "currently committed" field state; `null` simulates a missing item. */
    var currentFieldsForCounterMutation: Map<String, FirebaseValue>? = null

    /** Queue of per-page chunk sizes [clearCompletedChunk] reports, consumed one call at a time; an empty queue reports `0` (sweep done). */
    val clearChunkResultQueue: ArrayDeque<Int> = ArrayDeque()
    var clearChunkFailure: NSError? = null

    override fun observeItems(
        uid: String,
        listId: String,
        onSnapshot: (List<IosFirestoreItemDocument>) -> Unit,
        onError: (NSError) -> Unit,
    ): IosFirestoreListenerHandle {
        itemsAddCount++
        itemsListener = onSnapshot
        itemsErrorListener = onError
        return object : IosFirestoreListenerHandle {
            private var removed = false
            override fun remove() {
                if (removed) return
                removed = true
                itemsRemoveCount++
                itemsListener = null
                itemsErrorListener = null
            }
        }
    }

    override fun observeItem(
        uid: String,
        listId: String,
        itemId: String,
        onSnapshot: (IosFirestoreItemDocument?) -> Unit,
        onError: (NSError) -> Unit,
    ): IosFirestoreListenerHandle {
        itemAddCount++
        itemListener = onSnapshot
        itemErrorListener = onError
        return object : IosFirestoreListenerHandle {
            private var removed = false
            override fun remove() {
                if (removed) return
                removed = true
                itemRemoveCount++
                itemListener = null
                itemErrorListener = null
            }
        }
    }

    override fun addItem(
        uid: String,
        listId: String,
        fields: Map<String, FirebaseValue>,
        completion: (String?, NSError?) -> Unit,
    ) {
        addCalls += AddCall(uid, listId, fields)
        val failure = addFailure
        if (failure != null) {
            completion(null, failure)
        } else {
            completion(addedIdOverride ?: "generated-item-id-${addCalls.size}", null)
        }
    }

    override fun updateItemFields(
        uid: String,
        listId: String,
        itemId: String,
        fields: Map<String, FirebaseValue>,
        completion: (NSError?) -> Unit,
    ) {
        updateCalls += UpdateCall(uid, listId, itemId, fields)
        completion(updateFailure)
    }

    override fun mutateItemWithCounters(
        uid: String,
        listId: String,
        itemId: String,
        decide: (fields: Map<String, FirebaseValue>?) -> ItemCounterOutcome,
        completion: (NSError?) -> Unit,
    ) {
        val outcome = decide(currentFieldsForCounterMutation)
        counterMutationCalls += CounterMutationCall(uid, listId, itemId, outcome)
        completion(counterMutationFailure)
    }

    override fun clearCompletedChunk(
        uid: String,
        listId: String,
        chunkSize: Int,
        itemPatch: Map<String, FirebaseValue>,
        completion: (Int, NSError?) -> Unit,
    ) {
        clearChunkCalls += ClearChunkCall(uid, listId, chunkSize)
        val failure = clearChunkFailure
        if (failure != null) {
            completion(0, failure)
        } else {
            completion(if (clearChunkResultQueue.isEmpty()) 0 else clearChunkResultQueue.removeFirst(), null)
        }
    }

    /** Simulates the SDK delivering a fresh snapshot to a live [observeItems] listener. */
    fun emitItems(documents: List<IosFirestoreItemDocument>) {
        itemsListener?.invoke(documents)
    }

    fun emitItemsError(error: NSError) {
        itemsErrorListener?.invoke(error)
    }

    /** Simulates the SDK delivering a fresh snapshot to a live [observeItem] listener. */
    fun emitItem(document: IosFirestoreItemDocument?) {
        itemListener?.invoke(document)
    }

    fun emitItemError(error: NSError) {
        itemErrorListener?.invoke(error)
    }

    val hasLiveItemsListener: Boolean get() = itemsListener != null
    val hasLiveItemListener: Boolean get() = itemListener != null
}
