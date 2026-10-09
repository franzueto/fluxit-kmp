package com.fluxit.firebase.item

import com.fluxit.data.remote.ContractResult
import com.fluxit.data.remote.FieldPatch
import com.fluxit.data.remote.FirebaseDocumentDto
import com.fluxit.data.remote.FirebaseDocumentMapper
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.domain.FluxItem
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.RepositorySnapshot
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.WebAuthBridgeCurrentUidProvider
import com.fluxit.firebase.list.toRepositoryException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Web [ItemRepository] over the Firebase JS SDK, against
 * `users/{uid}/lists/{listId}/items/{itemId}`. Ported from `IosFirebaseItemRepository`
 * (decision D2) with the same counter policy: counter-affecting mutations hand a pure
 * `decide` lambda to [WebFirestoreItemBridge.mutateItemWithCounters], which calls it
 * inside a Firestore transaction with the item's freshly read fields and applies the
 * returned [ItemCounterOutcome]. The policy (what counts as active, which delta to
 * write) stays here and is unit-tested with a fake bridge; only the transaction
 * mechanics live in `firebase-bridge.mjs`.
 */
internal class WebFirebaseItemRepository(
    private val bridgeProvider: () -> WebFirestoreItemBridge,
    private val currentUid: CurrentUidProvider,
    /** Same role, same default, same validation as `AndroidFirebaseItemRepository.clearCompletedChunkSize`. */
    private val clearCompletedChunkSize: Int = DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE,
) : ItemRepository {

    init {
        requireValidClearCompletedChunkSize(clearCompletedChunkSize)
    }

    /** Production constructor: the JS bridge and the uid from the auth bridge, both read per call. */
    constructor() : this({ JsWebFirestoreItemBridge }, WebAuthBridgeCurrentUidProvider())

    // --- listeners (mapping/ordering, unmodified, mirrors the list repository) --

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
            onError = { error -> close(error.toRepositoryException()) },
        )
        awaitClose { handle.remove() }
    }

    /**
     * Real `isFromCache`/`hasPendingWrites` from the bridge's separate
     * `includeMetadataChanges: true` listener.
     */
    override fun observeItemsSnapshot(listId: String): Flow<RepositorySnapshot<List<FluxItem>>> = callbackFlow {
        val uid = currentUid.currentUid()
        val handle = bridgeProvider().observeItemsSnapshot(
            uid = uid,
            listId = listId,
            onSnapshot = { snapshot ->
                val now = nowMillis()
                val items = snapshot.documents
                    .map { it.toDto(now) }
                    .mapNotNull { dto -> (FirebaseDocumentMapper.item(listId, dto) as? ContractResult.Value)?.value }
                    .sortedWith(FirebaseDocumentMapper.itemOrdering)
                trySend(RepositorySnapshot(items, snapshot.isFromCache, snapshot.hasPendingWrites))
            },
            onError = { error -> close(error.toRepositoryException()) },
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
            onError = { error -> close(error.toRepositoryException()) },
        )
        awaitClose { handle.remove() }
    }

    // --- creation: full-initial-field-set write, exempt from field-scoped patches since the document is new -----------------

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
                    continuation.resumeWithException(error.toRepositoryException())
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    // --- field-scoped, no counters -----------------------------------------

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
                    continuation.resumeWithException(error.toRepositoryException())
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }

    // --- counter-affecting mutations: transactional read-then-delta -------------------
    // The read-then-decide policy below runs as a synchronous Kotlin lambda the JS
    // bridge invokes inside its transaction (see the class KDoc).

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
                    continuation.resumeWithException(error.toRepositoryException())
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
     * single-page query+batch mechanics live behind [WebFirestoreItemBridge.clearCompletedChunk]).
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
                        continuation.resumeWithException(error.toRepositoryException())
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

/** Firestore's hard per-batch operation cap (SDK-documented, not tunable) - same constant as Android's, redeclared per platform compilation. */
internal const val MAX_BATCH_WRITES = 500

/** See [WebFirebaseItemRepository]'s `clearCompletedChunkSize` KDoc for why this is well under [MAX_BATCH_WRITES]. Same value as Android's [com.fluxit.firebase.item] default (different compilation, see [MAX_BATCH_WRITES]'s KDoc). */
internal const val DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE = 400

/**
 * The pure invariant behind the `>500-item chunk strategy`, identical in shape and
 * value to Android's `AndroidFirebaseItemRepository`'s
 * `requireValidClearCompletedChunkSize` (a separate declaration - see [MAX_BATCH_WRITES]'s
 * KDoc for why this cannot literally be the same Kotlin declaration across platforms).
 * Extracted standalone specifically so it is unit-testable without a registered
 * [WebFirestoreItemBridge] - see `WebFirebaseItemRepositoryChunkSizeTest`.
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

/** Ordering fallback while a server timestamp is pending; see the list repository's `nowMillis()`. */
private fun nowMillis(): Long = currentTimeMillis().toLong()

private fun currentTimeMillis(): Double = js("Date.now()")

private fun WebFirestoreItemDocument.toDto(clientFallbackMillis: Long): FirebaseDocumentDto =
    FirebaseDocumentDto(id = id, fields = fields, clientFallbackMillis = clientFallbackMillis)
