package com.fluxit.firebase.item

import com.fluxit.data.remote.FirebaseValue
import com.fluxit.firebase.list.IosFirestoreListDocument
import com.fluxit.firebase.list.IosFirestoreListenerHandle
import platform.Foundation.NSError

/**
 * FB-205's wire-format document type is FB-203's [IosFirestoreListDocument] verbatim -
 * `id` + `fields: Map<String, FirebaseValue>` is exactly as list-agnostic as
 * `com.fluxit.firebase.list.ListRepositoryException`, which FB-204 already reused
 * unmodified on Android for the identical "the name says list, the shape doesn't care"
 * reason (see `AndroidFirebaseItemRepository`'s KDoc, `FB-204-NB3`). A parallel
 * `IosFirestoreItemDocument` data class with the same two fields would be pure
 * duplication with no behavioral difference. Flagged for reviewer, same as `FB-204-NB3`:
 * if a future error/wire-type-taxonomy pass ever renames these list-named-but-neutral
 * types, this typealias moves with it for free.
 */
typealias IosFirestoreItemDocument = IosFirestoreListDocument

/**
 * `FB-407`: item-scoped counterpart of [com.fluxit.firebase.list.IosFirestoreListSnapshot]
 * - see that type's KDoc for the full rationale (a dedicated data class, not two extra
 * `Boolean` closure parameters, to avoid `KotlinBoolean` boxing at the Swift call site).
 * Not reused as a second typealias of the list type: unlike [IosFirestoreItemDocument]
 * (identical shape to its list counterpart with no divergence expected), a future
 * item-specific field on this snapshot type (e.g. a per-item sync-conflict flag) is at
 * least plausible, whereas [IosFirestoreItemDocument]'s reuse rationale explicitly rests
 * on the shape never diverging.
 */
data class IosFirestoreItemSnapshot(
    val documents: List<IosFirestoreItemDocument>,
    val isFromCache: Boolean,
    val hasPendingWrites: Boolean,
)

/**
 * Outcome of the pure, synchronous read-then-decide policy for a counter-affecting item
 * mutation (`AndroidFirebaseItemRepository`'s FB-204 transaction body - read live
 * `isCompleted`/`deletedAt` first, then decide - expressed as data here rather than as
 * Kotlin code operating on a live SDK `Transaction`, because per `PLAN-008` only Swift
 * can open a Firestore transaction).
 *
 * [IosFirebaseItemRepository] computes this purely from the field values Swift hands
 * back from inside its own transaction `updateBlock` - never suspending - so
 * [IosFirestoreItemBridge.mutateItemWithCounters]'s `decide` callback can be invoked
 * synchronously, safely, and repeatedly (Firestore retries a transaction on write
 * contention, so `decide` may run more than once per call, always against the latest
 * committed field values - the same guarantee `runTransaction`'s automatic retry gives
 * Android's `Transaction.get`-then-decide body).
 *
 * `counterDelta` reuses FB-201's own [FirebaseValue.Number] rather than a raw `Long` or
 * a new Kotlin/Swift-crossing numeric convention: `Map<String, FirebaseValue.Number>` is
 * a proven-safe boundary type (already crossing in [IosFirestoreItemBridge.addItem]'s and
 * [com.fluxit.firebase.list.IosFirestoreListBridge.createList]'s `fields` maps), whereas
 * a boxed Kotlin `Long` in a generic `Map` crossing to Swift as `KotlinLong` is an
 * untested pattern in this codebase - reusing the already-exercised type is the more
 * conservative choice per the task's "reuse, do not invent a parallel encoding" guidance.
 */
sealed interface ItemCounterOutcome {

    /** The item is missing, or the mutation is a genuine no-op. No write at all - neither the item nor the list counters are touched. */
    data object NoOp : ItemCounterOutcome

    /** Field-scoped patch to the item document, plus a non-empty-only counter delta on the list document (only non-zero deltas are ever present). */
    data class ApplyPatch(
        val itemFields: Map<String, FirebaseValue>,
        val counterDelta: Map<String, FirebaseValue.Number>,
    ) : ItemCounterOutcome

    /** Hard-deletes the item document, plus a non-empty-only counter delta on the list document. */
    data class HardDelete(val counterDelta: Map<String, FirebaseValue.Number>) : ItemCounterOutcome
}

/**
 * The Swift-implemented seam through which FB-205's iOS item adapter reaches Cloud
 * Firestore's `users/{uid}/lists/{listId}/items/{itemId}` collection (PLAN-008, same
 * reason [com.fluxit.firebase.list.IosFirestoreListBridge] exists for lists -
 * `iosApp/iosApp/FirebaseItemBridge.swift`).
 *
 * Same field-mask/changed-keys surface FB-203 designed for this task to reuse: every
 * write-shaped method takes a `Map<String, FirebaseValue>` (or, for the counter path,
 * [ItemCounterOutcome]'s `Map<String, FirebaseValue.Number>`), identical in spirit to
 * [com.fluxit.data.remote.FieldPatch.fields]. No parallel value-encoding type is
 * introduced here.
 *
 * Every completion handler must be invoked exactly once, on any thread. `null` means
 * success, mirroring [IosFirestoreListBridge]'s and
 * [com.fluxit.firebase.auth.IosAuthBridge]'s convention exactly.
 */
interface IosFirestoreItemBridge {

    /** Same shape as [com.fluxit.firebase.list.IosFirestoreListBridge.observeListSummaries], scoped to one list's items subcollection. */
    fun observeItems(
        uid: String,
        listId: String,
        onSnapshot: (List<IosFirestoreItemDocument>) -> Unit,
        onError: (NSError) -> Unit,
    ): IosFirestoreListenerHandle

    /**
     * `FB-407`: same shape as
     * [com.fluxit.firebase.list.IosFirestoreListBridge.observeListSummariesSnapshot] -
     * see that method's KDoc for why this is a genuinely separate `includeMetadataChanges:
     * true` listener registration, not a shared one with [observeItems].
     */
    fun observeItemsSnapshot(
        uid: String,
        listId: String,
        onSnapshot: (IosFirestoreItemSnapshot) -> Unit,
        onError: (NSError) -> Unit,
    ): IosFirestoreListenerHandle

    /** Same shape as [com.fluxit.firebase.list.IosFirestoreListBridge.observeList], scoped to a single item document. */
    fun observeItem(
        uid: String,
        listId: String,
        itemId: String,
        onSnapshot: (IosFirestoreItemDocument?) -> Unit,
        onError: (NSError) -> Unit,
    ): IosFirestoreListenerHandle

    /**
     * Creates a brand-new item document with an auto-generated ID and, in the same
     * atomic batched write, increments the parent list's `totalItems` by exactly 1.
     *
     * `DEC-003d-1`: the same creation-only exemption `createList`
     * (`IosFirestoreListBridge.createList`) uses, extended to `AndroidFirebaseItemRepository`'s
     * FB-204 shape of "one atomic batch, not a bare `setData`" because this write also
     * has to move a sibling document's counter - a fresh auto-ID item document has no
     * prior state and no possible concurrent writer, so its whole-initial-field-set
     * `setData(_:)` does not contradict `DEC-003d`, and the `totalItems` increment is
     * unconditional per FB-204's own settled precedent (not re-litigated here).
     */
    fun addItem(
        uid: String,
        listId: String,
        fields: Map<String, FirebaseValue>,
        completion: (String?, NSError?) -> Unit,
    )

    /**
     * Applies a field-scoped patch to an existing item document via `updateData(_:)` -
     * never `setData(_:)`, never touching counters. Backs `updateItem`/`setPhotoRef`,
     * which - matching `AndroidFirebaseItemRepository`'s and
     * `AndroidFirebaseListRepository`'s bare-`update()` precedent - throw `NOT_FOUND`
     * when the item document does not exist, by Firestore's own default `updateData(_:)`
     * behavior on a missing document (no special-casing needed on either platform).
     */
    fun updateItemFields(
        uid: String,
        listId: String,
        itemId: String,
        fields: Map<String, FirebaseValue>,
        completion: (NSError?) -> Unit,
    )

    /**
     * Runs [decide] synchronously inside a real Firestore transaction: reads the item
     * document first, hands its current fields (`null` if the document does not exist)
     * to [decide], then applies whatever [ItemCounterOutcome] it returns - atomically,
     * with the SDK's own automatic retry-on-contention. Backs `setCompleted`,
     * `softDeleteItem`, `restoreItem`, and `deleteItem`, exactly the same four mutations
     * `AndroidFirebaseItemRepository`'s FB-204 `runTransaction` body backs.
     */
    fun mutateItemWithCounters(
        uid: String,
        listId: String,
        itemId: String,
        decide: (fields: Map<String, FirebaseValue>?) -> ItemCounterOutcome,
        completion: (NSError?) -> Unit,
    )

    /**
     * Queries up to [chunkSize] currently active-and-completed items, tombstones them
     * with [itemPatch] and decrements the list's `totalItems`/`completedItems` by the
     * page's real size - all in one atomic `WriteBatch`-equivalent - then reports how
     * many documents this single page touched (`0` means nothing matched: the caller's
     * `clearCompleted` loop is done). [IosFirebaseItemRepository] owns the re-query-until-
     * empty loop and the runaway-iteration guard, exactly mirroring
     * `AndroidFirebaseItemRepository.clearCompleted`'s division of responsibility
     * (Kotlin owns the resumable loop; the platform SDK call is one page at a time).
     */
    fun clearCompletedChunk(
        uid: String,
        listId: String,
        chunkSize: Int,
        itemPatch: Map<String, FirebaseValue>,
        completion: (Int, NSError?) -> Unit,
    )
}

/**
 * Hand-off point between the Swift app layer and the Kotlin framework, exactly
 * mirroring [com.fluxit.firebase.list.IosFirestoreListBridgeRegistry].
 */
object IosFirestoreItemBridgeRegistry {

    private var registered: IosFirestoreItemBridge? = null

    /** Called once from Swift, from `FirebaseBootstrap.start()`. */
    fun register(bridge: IosFirestoreItemBridge) {
        registered = bridge
    }

    /** Test/diagnostic accessor; `null` before the app layer has registered. */
    fun bridgeOrNull(): IosFirestoreItemBridge? = registered

    internal fun requireBridge(): IosFirestoreItemBridge = checkNotNull(registered) {
        "No IosFirestoreItemBridge registered. FirebaseBootstrap.start() must run - and " +
            "must call IosFirestoreItemBridgeRegistry.register - before ItemRepository is " +
            "resolved."
    }
}
