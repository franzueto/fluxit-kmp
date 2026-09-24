@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fluxit.firebase.item

import com.fluxit.data.remote.ContractResult
import com.fluxit.data.remote.FieldPatch
import com.fluxit.data.remote.FirebaseDocumentDto
import com.fluxit.data.remote.FirebaseDocumentMapper
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.domain.FluxItem
import com.fluxit.domain.ItemRepository
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.IosAuthBridgeCurrentUidProvider
import com.fluxit.firebase.list.toListRepositoryException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.time

/**
 * iOS [ItemRepository] backed by the official Firebase Apple Firestore SDK (FB-205),
 * against the same `users/{uid}/lists/{listId}/items/{itemId}` path
 * `AndroidFirebaseItemRepository` (FB-204) uses.
 *
 * Ported from FB-204 onto the FB-203 `IosFirestoreListBridge`-style Kotlin-
 * protocol/Swift-implementation pattern, exactly as FB-203 ported FB-202. Reuse
 * decisions (module-scoped reuse from `com.fluxit.firebase.list`, the FB-203 sibling
 * package - none of this is re-derived here, mirroring `AndroidFirebaseItemRepository`'s
 * own KDoc structure):
 *
 * - [CurrentUidProvider]/[IosAuthBridgeCurrentUidProvider] - uid resolution has nothing
 *   to do with lists vs. items, reused unmodified. Resolved fresh per call (never
 *   cached), same Phase 1 constraint [com.fluxit.firebase.list.IosFirebaseListRepository]
 *   documents.
 * - `IosFirestoreErrorMapping.kt`'s `toListRepositoryException()`/
 *   [com.fluxit.firebase.list.ListRepositoryException] reused unmodified rather than
 *   duplicated into an iOS `ItemRepositoryException`, for the identical reason FB-204
 *   gave on Android (`FB-204-NB3`, carried forward here rather than re-litigated).
 * - [IosFirestoreItemDocument] is FB-203's [com.fluxit.firebase.list.IosFirestoreListDocument]
 *   itself (see that typealias's KDoc) - not a duplicate wire-format type.
 *
 * What genuinely cannot be reused, because it is platform-mechanism-specific rather
 * than business policy (flagged per the task's request to call out any Android-shape
 * divergence explicitly): Android's counter-affecting mutations run their read-then-
 * decide body as ordinary Kotlin code inside `firestore.runTransaction { transaction ->
 * ... }`, because the Android Firestore SDK is directly reachable from `androidMain`
 * Kotlin. Per `PLAN-008`, no Firestore SDK type is reachable from `iosMain` at all, so
 * only Swift can open a transaction. [IosFirestoreItemBridge.mutateItemWithCounters]
 * bridges this by having Swift call back into a synchronous (never-suspending) Kotlin
 * `decide` lambda **from inside** its transaction's `updateBlock`, passing the
 * transaction-read field values in and getting an [ItemCounterOutcome] back out. This
 * keeps 100% of the counter *policy* (what counts as "was active", what delta to write)
 * in Kotlin, unit-testable exactly like Android's via a fake bridge - only the mechanics
 * of *opening a transaction and applying the outcome's writes* live in Swift. See
 * `FirebaseItemBridge.mutateItemWithCounters`'s KDoc for why this uses the plain
 * completion-handler `runTransaction(_:completion:)` overload, not `async throws`
 * (`FB-203`'s crash finding).
 */
class IosFirebaseItemRepository internal constructor(
    private val bridgeProvider: () -> IosFirestoreItemBridge,
    private val currentUid: CurrentUidProvider,
    /** Same role, same default, same validation as `AndroidFirebaseItemRepository.clearCompletedChunkSize`. */
    private val clearCompletedChunkSize: Int = DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE,
) : ItemRepository {

    init {
        requireValidClearCompletedChunkSize(clearCompletedChunkSize)
    }

    /**
     * Production constructor: the bridge Swift registered in
     * [IosFirestoreItemBridgeRegistry], and the uid read through the already-registered
     * `IosAuthBridge` (same seam [com.fluxit.firebase.list.IosFirebaseListRepository]
     * uses). Both resolved lazily, per call, not at construction time.
     */
    constructor() : this(IosFirestoreItemBridgeRegistry::requireBridge, IosAuthBridgeCurrentUidProvider())

    // --- listeners (FB-201 mapping/ordering, unmodified, mirrors the list repository) --

    override fun observeItems(listId: String): Flow<List<FluxItem>> = callbackFlow {
        val uid = currentUid.currentUid()
        val handle = bridgeProvider().observeItems(
            uid = uid,
            listId = listId,
            onSnapshot = { documents ->
                val now = nowMillis()
                val items = documents
                    .map { it.toDto(now) }
                    .mapNotNull { dto -> (FirebaseDocumentMapper.item(listId, dto) as? ContractResult.Value)?.value }
                    .sortedWith(FirebaseDocumentMapper.itemOrdering)
                trySend(items)
            },
            onError = { error -> close(error.toListRepositoryException()) },
        )
        awaitClose { handle.remove() }
    }

    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> = callbackFlow {
        val uid = currentUid.currentUid()
        val handle = bridgeProvider().observeItem(
            uid = uid,
            listId = listId,
            itemId = itemId,
            onSnapshot = { document ->
                if (document == null) {
                    trySend(null)
                } else {
                    val dto = document.toDto(nowMillis())
                    val item = (FirebaseDocumentMapper.item(listId, dto) as? ContractResult.Value)?.value
                    trySend(item)
                }
            },
            onError = { error -> close(error.toListRepositoryException()) },
        )
        awaitClose { handle.remove() }
    }

    // --- creation: full-initial-field-set write, DEC-003d-1 exemption -----------------

    override suspend fun addItem(listId: String, title: String) {
        val uid = currentUid.currentUid()
        val initialFields = mapOf(
            FirebaseSchema.Fields.LIST_ID to FirebaseValue.Text(listId),
            FirebaseSchema.Fields.TITLE to FirebaseValue.Text(title),
            FirebaseSchema.Fields.DESCRIPTION to FirebaseValue.Null,
            FirebaseSchema.Fields.IS_COMPLETED to FirebaseValue.Bool(false),
            FirebaseSchema.Fields.PHOTO_REF to FirebaseValue.Null,
            FirebaseSchema.Fields.CREATED_AT to FirebaseValue.PendingServerTimestamp,
            FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
            FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
            FirebaseSchema.Fields.SCHEMA_VERSION to FirebaseValue.Number(FirebaseSchema.CURRENT_VERSION),
        )
        suspendCancellableCoroutine<Unit> { continuation ->
            bridgeProvider().addItem(uid, listId, initialFields) { _, error ->
                if (error != null) {
                    continuation.resumeWithException(error.toListRepositoryException())
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    // --- field-scoped, no counters (DEC-003d) -----------------------------------------

    override suspend fun updateItem(listId: String, itemId: String, title: String, description: String?) {
        applyPatch(
            listId,
            itemId,
            FieldPatch(
                mapOf(
                    FirebaseSchema.Fields.TITLE to FirebaseValue.Text(title),
                    FirebaseSchema.Fields.DESCRIPTION to (description?.let { FirebaseValue.Text(it) } ?: FirebaseValue.Null),
                    FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                )
            ),
        )
    }

    override suspend fun setPhotoRef(listId: String, itemId: String, photoRef: String?) {
        applyPatch(
            listId,
            itemId,
            FieldPatch(
                mapOf(
                    FirebaseSchema.Fields.PHOTO_REF to (photoRef?.let { FirebaseValue.Text(it) } ?: FirebaseValue.Null),
                    FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                )
            ),
        )
    }

    private suspend fun applyPatch(listId: String, itemId: String, patch: FieldPatch) {
        val uid = currentUid.currentUid()
        suspendCancellableCoroutine<Unit> { continuation ->
            bridgeProvider().updateItemFields(uid, listId, itemId, patch.fields) { error ->
                if (error != null) {
                    continuation.resumeWithException(error.toListRepositoryException())
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    // --- counter-affecting mutations: transactional read-then-delta -------------------
    // See the class KDoc for why the read-then-decide policy below runs as a synchronous
    // Kotlin lambda invoked *from inside* Swift's transaction block, rather than as
    // Kotlin code directly operating on a Transaction object (impossible per PLAN-008).

    /**
     * Idempotent: [decide] is handed the transaction's freshly-read field values every
     * time (including on an SDK-driven retry), so a repeated call with the same
     * [completed] value computes a zero counter delta and only rewrites the item's own
     * fields, never double-incrementing/decrementing `completedItems`. Tombstoned items
     * are skipped for the counter. Missing item: silent no-op.
     */
    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean) {
        runCounterMutation(listId, itemId) { fields ->
            if (fields == null) return@runCounterMutation ItemCounterOutcome.NoOp
            val wasCompleted = fields.boolOrFalse(FirebaseSchema.Fields.IS_COMPLETED)
            val isActive = fields.isActive()
            val patch = mapOf(
                FirebaseSchema.Fields.IS_COMPLETED to FirebaseValue.Bool(completed),
                FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
            )
            val delta = if (isActive && wasCompleted != completed) {
                mapOf(FirebaseSchema.Fields.COMPLETED_ITEMS to FirebaseValue.Number(if (completed) 1L else -1L))
            } else {
                emptyMap()
            }
            ItemCounterOutcome.ApplyPatch(patch, delta)
        }
    }

    /**
     * Idempotent: only decrements counters when the item was actually active before this
     * call. `completedItems` is only decremented if the item was both active *and*
     * completed, matching Room's `ItemDao` counter scope exactly. Missing item: silent
     * no-op.
     */
    override suspend fun softDeleteItem(listId: String, itemId: String) {
        runCounterMutation(listId, itemId) { fields ->
            if (fields == null) return@runCounterMutation ItemCounterOutcome.NoOp
            val wasActive = fields.isActive()
            val wasCompleted = fields.boolOrFalse(FirebaseSchema.Fields.IS_COMPLETED)
            val patch = mapOf(
                FirebaseSchema.Fields.DELETED_AT to FirebaseValue.PendingServerTimestamp,
                FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
            )
            val delta = if (wasActive) counterDelta(total = -1L, completed = if (wasCompleted) -1L else null) else emptyMap()
            ItemCounterOutcome.ApplyPatch(patch, delta)
        }
    }

    /** Symmetric with [softDeleteItem]: only increments counters when the item was actually tombstoned before this call. Missing item: silent no-op. */
    override suspend fun restoreItem(listId: String, itemId: String) {
        runCounterMutation(listId, itemId) { fields ->
            if (fields == null) return@runCounterMutation ItemCounterOutcome.NoOp
            val wasTombstoned = !fields.isActive()
            val wasCompleted = fields.boolOrFalse(FirebaseSchema.Fields.IS_COMPLETED)
            val patch = mapOf(
                FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
                FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
            )
            val delta = if (wasTombstoned) counterDelta(total = 1L, completed = if (wasCompleted) 1L else null) else emptyMap()
            ItemCounterOutcome.ApplyPatch(patch, delta)
        }
    }

    /**
     * Genuine hard delete (mirrors Room's `ItemDao.delete`, distinct from
     * [softDeleteItem]'s tombstone). Only decrements counters if the item was still
     * active - a hard delete of an item already tombstoned by a prior [softDeleteItem]
     * must not double-decrement. Missing item: silent no-op.
     */
    override suspend fun deleteItem(listId: String, itemId: String) {
        runCounterMutation(listId, itemId) { fields ->
            if (fields == null) return@runCounterMutation ItemCounterOutcome.NoOp
            val wasActive = fields.isActive()
            val wasCompleted = fields.boolOrFalse(FirebaseSchema.Fields.IS_COMPLETED)
            val delta = if (wasActive) counterDelta(total = -1L, completed = if (wasCompleted) -1L else null) else emptyMap()
            ItemCounterOutcome.HardDelete(delta)
        }
    }

    private suspend fun runCounterMutation(
        listId: String,
        itemId: String,
        decide: (fields: Map<String, FirebaseValue>?) -> ItemCounterOutcome,
    ) {
        val uid = currentUid.currentUid()
        suspendCancellableCoroutine<Unit> { continuation ->
            bridgeProvider().mutateItemWithCounters(uid, listId, itemId, decide) { error ->
                if (error != null) {
                    continuation.resumeWithException(error.toListRepositoryException())
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    private fun counterDelta(total: Long?, completed: Long?): Map<String, FirebaseValue.Number> = buildMap {
        if (total != null) put(FirebaseSchema.Fields.TOTAL_ITEMS, FirebaseValue.Number(total))
        if (completed != null) put(FirebaseSchema.Fields.COMPLETED_ITEMS, FirebaseValue.Number(completed))
    }

    /**
     * Tombstones every currently active-and-completed item in [listId], chunked under
     * Firestore's 500-writes-per-batch cap, exactly mirroring
     * `AndroidFirebaseItemRepository.clearCompleted`'s KDoc (resumability, idempotency on
     * retry, and the "each chunk is atomic, the whole multi-chunk sweep is not" caveat
     * all apply identically here - the loop itself is platform-neutral Kotlin, only the
     * single-page query+batch mechanics live behind [IosFirestoreItemBridge.clearCompletedChunk]).
     */
    override suspend fun clearCompleted(listId: String) {
        val uid = currentUid.currentUid()
        val itemPatch = mapOf(
            FirebaseSchema.Fields.DELETED_AT to FirebaseValue.PendingServerTimestamp,
            FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
        )
        var iterations = 0
        while (true) {
            iterations++
            check(iterations <= MAX_CLEAR_COMPLETED_ITERATIONS) {
                "clearCompleted did not converge after $MAX_CLEAR_COMPLETED_ITERATIONS chunks for list $listId"
            }
            val chunkCount = suspendCancellableCoroutine<Int> { continuation ->
                bridgeProvider().clearCompletedChunk(uid, listId, clearCompletedChunkSize, itemPatch) { count, error ->
                    if (error != null) {
                        continuation.resumeWithException(error.toListRepositoryException())
                    } else {
                        continuation.resume(count)
                    }
                }
            }
            if (chunkCount == 0) break
        }
    }

    private companion object {
        /** Defensive-only: guards against a future regression turning this into an infinite loop. Same value as Android's. */
        const val MAX_CLEAR_COMPLETED_ITERATIONS = 100_000
    }
}

/** Firestore's hard per-batch operation cap (SDK-documented, not tunable) - same constant as Android's, redeclared because `androidMain`/`iosMain` are separate compilations with no shared `internal` linkage. */
internal const val MAX_BATCH_WRITES = 500

/** See [IosFirebaseItemRepository]'s `clearCompletedChunkSize` KDoc for why this is well under [MAX_BATCH_WRITES]. Same value as Android's [com.fluxit.firebase.item] default (different compilation, see [MAX_BATCH_WRITES]'s KDoc). */
internal const val DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE = 400

/**
 * The pure invariant behind FB-205's `>500-item chunk strategy`, identical in shape and
 * value to Android's `AndroidFirebaseItemRepository`'s
 * `requireValidClearCompletedChunkSize` (a separate declaration - see [MAX_BATCH_WRITES]'s
 * KDoc for why this cannot literally be the same Kotlin declaration across platforms).
 * Extracted standalone specifically so it is unit-testable without a registered
 * [IosFirestoreItemBridge] - see `IosFirebaseItemRepositoryChunkSizeTest`.
 */
internal fun requireValidClearCompletedChunkSize(chunkSize: Int, maxBatchWrites: Int = MAX_BATCH_WRITES) {
    require(chunkSize in 1..maxBatchWrites - 1) {
        "clearCompletedChunkSize must leave room for the trailing counter-update write " +
            "in the same batch (Firestore's hard cap is $maxBatchWrites writes/batch), " +
            "got $chunkSize"
    }
}

/** `true` when `deletedAt` is absent or explicitly [FirebaseValue.Null] - mirrors Android's `snapshot.get(DELETED_AT) == null` check on a raw `DocumentSnapshot`. */
private fun Map<String, FirebaseValue>.isActive(): Boolean =
    this[FirebaseSchema.Fields.DELETED_AT].let { it == null || it == FirebaseValue.Null }

/** Mirrors Android's `snapshot.getBoolean(IS_COMPLETED) ?: false`. */
private fun Map<String, FirebaseValue>.boolOrFalse(field: String): Boolean =
    (this[field] as? FirebaseValue.Bool)?.value ?: false

/**
 * Captured when a local snapshot is received; used only while a server timestamp is
 * pending. Same mechanism and precision rationale as
 * [com.fluxit.firebase.list.IosFirebaseListRepository]'s own `nowMillis()` (file-private
 * there, so redeclared here rather than widening its visibility for an FB-205 concern -
 * same call `AndroidFirebaseItemRepository` made for its local `awaitTaskResult()`).
 */
private fun nowMillis(): Long = time(null) * 1000L

private fun IosFirestoreItemDocument.toDto(clientFallbackMillis: Long): FirebaseDocumentDto =
    FirebaseDocumentDto(id = id, fields = fields, clientFallbackMillis = clientFallbackMillis)
