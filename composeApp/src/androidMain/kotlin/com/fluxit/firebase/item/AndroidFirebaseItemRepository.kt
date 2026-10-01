package com.fluxit.firebase.item

import com.fluxit.data.remote.ContractResult
import com.fluxit.data.remote.FieldPatch
import com.fluxit.data.remote.FirebaseDocumentMapper
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.domain.FluxItem
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.RepositorySnapshot
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.FirebaseAuthCurrentUidProvider
import com.fluxit.firebase.list.FirestoreValueCodec
import com.fluxit.firebase.list.toListRepositoryException
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android [ItemRepository] backed by the official Firebase Android Firestore SDK (FB-204),
 * against the `users/{uid}/lists/{listId}/items/{itemId}` path (`PLAN-006`/`PLAN-007`;
 * [FirebaseSchema.itemPath]).
 *
 * Reuse decisions (module-scoped `internal` reuse from [com.fluxit.firebase.list], the
 * FB-202 sibling package, per FB-204's task brief - none of this is re-derived here):
 *
 * - [com.fluxit.firebase.list.FirestoreValueCodec] - pure value translation, entirely
 *   list-agnostic, reused unmodified for both single-value/[FieldPatch] encoding and raw
 *   document decoding.
 * - [CurrentUidProvider]/[FirebaseAuthCurrentUidProvider] - uid resolution has nothing to
 *   do with lists vs. items, reused unmodified. Resolved fresh per call (never cached),
 *   same Phase 1 constraint [com.fluxit.firebase.list.AndroidFirebaseListRepository] documents.
 * - `FirestoreErrorMapping.kt`'s `toListRepositoryException()`/[com.fluxit.firebase.list.ListRepositoryException]
 *   reused unmodified rather than duplicated into an `ItemRepositoryException`: nothing in
 *   this codebase yet catches by that class name specifically (only [com.fluxit.data.remote.ApplicationError]
 *   is a cross-platform-visible concept - see `FirebaseContracts.kt`), so a same-named,
 *   differently-scoped wrapper class would add indirection with no behavioral or
 *   call-site benefit. If a future phase (FB-401's error taxonomy) starts catching by
 *   this class name specifically, renaming it to something list/item-neutral (e.g.
 *   `FirestoreRepositoryException`) becomes the right fix - not forking it here.
 *
 * Counter atomicity/idempotency (FB-204's core acceptance criterion): every mutation that
 * can change `totalItems`/`completedItems` reads the item's *current* field values first
 * (`Transaction.get`, always before any write in the same transaction, per the SDK's own
 * rule) and computes a delta from that live state, never a blind `+1`/`-1`. Two important
 * consequences:
 *
 * 1. Retried/duplicate calls converge instead of double-applying: e.g. two concurrent
 *    `setCompleted(true)` calls on the same item both read `wasCompleted`, but only the
 *    one that is *not* already `true` computes a non-zero delta - the SDK's automatic
 *    transaction retry-on-contention (documented on [FirebaseFirestore.runTransaction])
 *    guarantees this read-then-decide happens serially against the latest committed value,
 *    so `completedItems` is incremented exactly once, not twice.
 * 2. A tombstoned (soft-deleted) item is already excluded from both counters
 *    ([com.fluxit.data.Daos.ItemDao]'s `deletedAt IS NULL` scoping, mirrored here), so
 *    [softDeleteItem]/[restoreItem]/[deleteItem] only touch the counters when the item's
 *    prior active/tombstoned state actually changes - a second `softDeleteItem` call on an
 *    already-tombstoned item is a genuine no-op for the counters, not a double-decrement.
 *
 * Missing-item semantics deliberately mirror Room's `UPDATE ... WHERE listId=? AND id=?`/
 * `DELETE ... WHERE listId=? AND id=?` no-match-is-a-no-op behavior (see
 * `RoomItemRepository`/`ItemDao` in `commonMain`'s `Repositories.kt`/`Daos.kt`) for every
 * counter-affecting mutation ([setCompleted], [softDeleteItem], [restoreItem],
 * [deleteItem]): the transaction reads the item first and silently does nothing if it no
 * longer exists, rather than surfacing a `NOT_FOUND` [com.fluxit.data.remote.ApplicationError].
 * This is also what makes retrying any of these calls after a concurrent hard delete safe.
 * [updateItem]/[setPhotoRef] do **not** get this treatment - they are plain field-scoped
 * `update()` calls (no transaction, no counters, DEC-003d), and Firestore's bare
 * `DocumentReference.update()` throws `NOT_FOUND` on a missing document by design. This
 * mirrors [com.fluxit.firebase.list.AndroidFirebaseListRepository]'s `updateList`/
 * `softDeleteList`/`restoreList`, which have the same bare-`update()`-throws-NOT_FOUND
 * behavior against Room's originally-silent-no-op `ListDao.setDeletedAt` - an asymmetry
 * already accepted at FB-202, not a new one introduced here. Flagged for the reviewer.
 */
class AndroidFirebaseItemRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val currentUid: CurrentUidProvider = FirebaseAuthCurrentUidProvider(),
    /**
     * Max item documents fetched/tombstoned per `clearCompleted` batch. One slot is
     * always reserved for the trailing list-counter-update write in the same batch, so
     * this must stay comfortably under Firestore's hard 500-write-per-batch cap - the
     * [DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE] default (400) leaves generous headroom rather
     * than maxing out at 499, deliberately, since this repository has no control over
     * whether a future change adds a second trailing write to the same batch.
     */
    private val clearCompletedChunkSize: Int = DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE,
) : ItemRepository {

    init {
        requireValidClearCompletedChunkSize(clearCompletedChunkSize)
    }

    private fun listDoc(uid: String, listId: String): DocumentReference =
        firestore.collection(FirebaseSchema.USERS).document(uid).collection(FirebaseSchema.LISTS).document(listId)

    private fun itemsCollection(uid: String, listId: String): CollectionReference =
        listDoc(uid, listId).collection(FirebaseSchema.ITEMS)

    // --- listeners (FB-201 mapping/ordering, unmodified) -----------------------------

    override fun observeItems(listId: String): Flow<List<FluxItem>> = callbackFlow {
        val uid = currentUid.currentUid()
        val registration = itemsCollection(uid, listId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error.toListRepositoryException())
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            trySend(mapItems(listId, snapshot))
        }
        awaitClose { registration.remove() }
    }

    /**
     * `FB-407`: same shape as `AndroidFirebaseListRepository.observeListSummariesSnapshot`
     * - a genuinely separate listener registered with [MetadataChanges.INCLUDE], reporting
     * the real [QuerySnapshot.getMetadata] `isFromCache`/`hasPendingWrites` into
     * [RepositorySnapshot], while [observeItems] keeps its own unmodified default-metadata
     * registration/behavior. See that method's KDoc for why this is not a shared listener.
     */
    override fun observeItemsSnapshot(listId: String): Flow<RepositorySnapshot<List<FluxItem>>> = callbackFlow {
        val uid = currentUid.currentUid()
        val registration = itemsCollection(uid, listId).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                close(error.toListRepositoryException())
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            trySend(
                RepositorySnapshot(
                    value = mapItems(listId, snapshot),
                    isFromCache = snapshot.metadata.isFromCache,
                    hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                ),
            )
        }
        awaitClose { registration.remove() }
    }

    /** FB-201 mapping/ordering, shared by [observeItems] and [observeItemsSnapshot]. */
    private fun mapItems(listId: String, snapshot: QuerySnapshot): List<FluxItem> {
        val now = System.currentTimeMillis()
        return snapshot.documents
            .mapNotNull { doc ->
                val dto = FirestoreValueCodec.decode(doc.id, doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE), now)
                (FirebaseDocumentMapper.item(listId, dto) as? ContractResult.Value)?.value
            }
            .sortedWith(FirebaseDocumentMapper.itemOrdering)
    }

    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> = callbackFlow {
        val uid = currentUid.currentUid()
        val registration = itemsCollection(uid, listId).document(itemId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error.toListRepositoryException())
                return@addSnapshotListener
            }
            if (snapshot == null || !snapshot.exists()) {
                trySend(null)
                return@addSnapshotListener
            }
            val now = System.currentTimeMillis()
            val dto = FirestoreValueCodec.decode(snapshot.id, snapshot.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE), now)
            val item = (FirebaseDocumentMapper.item(listId, dto) as? ContractResult.Value)?.value
            trySend(item)
        }
        awaitClose { registration.remove() }
    }

    // --- creation: full-initial-field-set `.set()`, DEC-003d-1 exemption -------------

    override suspend fun addItem(listId: String, title: String) {
        val uid = currentUid.currentUid()
        val itemRef = itemsCollection(uid, listId).document()
        val listRef = listDoc(uid, listId)
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
        val encoded = initialFields.mapValues { (_, value) -> FirestoreValueCodec.encode(value) }
        runFirestoreCall {
            firestore.batch()
                .set(itemRef, encoded)
                .update(listRef, mapOf(FirebaseSchema.Fields.TOTAL_ITEMS to FieldValue.increment(1L)))
                .commit()
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
        val reference = itemsCollection(uid, listId).document(itemId)
        runFirestoreCall { reference.update(FirestoreValueCodec.encode(patch)) }
    }

    // --- counter-affecting mutations: transactional read-then-delta ------------------

    /**
     * Idempotent: the transaction reads `isCompleted`/`deletedAt` fresh, so a repeated
     * call with the same [completed] value computes a zero delta and touches only the
     * item document's own fields, never double-incrementing/decrementing
     * `completedItems`. Tombstoned items are skipped for the counter (already excluded).
     * Missing item: silent no-op (mirrors `ItemDao.setCompleted`'s no-match UPDATE).
     */
    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean) {
        val uid = currentUid.currentUid()
        val itemRef = itemsCollection(uid, listId).document(itemId)
        val listRef = listDoc(uid, listId)
        runItemCounterTransaction(itemRef) { transaction, snapshot ->
            if (snapshot.exists()) {
                val wasCompleted = snapshot.getBoolean(FirebaseSchema.Fields.IS_COMPLETED) ?: false
                val isActive = snapshot.get(FirebaseSchema.Fields.DELETED_AT) == null
                transaction.update(
                    itemRef,
                    FirestoreValueCodec.encode(
                        FieldPatch(
                            mapOf(
                                FirebaseSchema.Fields.IS_COMPLETED to FirebaseValue.Bool(completed),
                                FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                            )
                        ),
                    ),
                )
                if (isActive && wasCompleted != completed) {
                    val delta = if (completed) 1L else -1L
                    transaction.update(listRef, mapOf(FirebaseSchema.Fields.COMPLETED_ITEMS to FieldValue.increment(delta)))
                }
            }
        }
    }

    /**
     * Idempotent: only decrements counters when the item was actually active before this
     * call (a second `softDeleteItem` on an already-tombstoned item is a pure no-op for
     * `totalItems`/`completedItems`). `completedItems` is only decremented if the item
     * was both active *and* completed, matching `ItemDao`'s `deletedAt IS NULL AND
     * isCompleted` counter scope exactly. Missing item: silent no-op.
     */
    override suspend fun softDeleteItem(listId: String, itemId: String) {
        val uid = currentUid.currentUid()
        val itemRef = itemsCollection(uid, listId).document(itemId)
        val listRef = listDoc(uid, listId)
        runItemCounterTransaction(itemRef) { transaction, snapshot ->
            if (snapshot.exists()) {
                val wasActive = snapshot.get(FirebaseSchema.Fields.DELETED_AT) == null
                val wasCompleted = snapshot.getBoolean(FirebaseSchema.Fields.IS_COMPLETED) ?: false
                transaction.update(
                    itemRef,
                    FirestoreValueCodec.encode(
                        FieldPatch(
                            mapOf(
                                FirebaseSchema.Fields.DELETED_AT to FirebaseValue.PendingServerTimestamp,
                                FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                            )
                        ),
                    ),
                )
                if (wasActive) {
                    transaction.update(listRef, counterDelta(total = -1L, completed = if (wasCompleted) -1L else null))
                }
            }
        }
    }

    /**
     * Symmetric with [softDeleteItem]: only increments counters when the item was
     * actually tombstoned before this call. Restoring an already-active item is a pure
     * no-op for the counters. Missing item: silent no-op.
     */
    override suspend fun restoreItem(listId: String, itemId: String) {
        val uid = currentUid.currentUid()
        val itemRef = itemsCollection(uid, listId).document(itemId)
        val listRef = listDoc(uid, listId)
        runItemCounterTransaction(itemRef) { transaction, snapshot ->
            if (snapshot.exists()) {
                val wasTombstoned = snapshot.get(FirebaseSchema.Fields.DELETED_AT) != null
                val wasCompleted = snapshot.getBoolean(FirebaseSchema.Fields.IS_COMPLETED) ?: false
                transaction.update(
                    itemRef,
                    FirestoreValueCodec.encode(
                        FieldPatch(
                            mapOf(
                                FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
                                FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                            )
                        ),
                    ),
                )
                if (wasTombstoned) {
                    transaction.update(listRef, counterDelta(total = 1L, completed = if (wasCompleted) 1L else null))
                }
            }
        }
    }

    /**
     * Genuine hard delete (mirrors `ItemDao.delete`, distinct from [softDeleteItem]'s
     * tombstone). Only decrements counters if the item was still active - a hard delete
     * of an item already tombstoned by a prior [softDeleteItem] must not double-decrement
     * (that tombstone call already applied its own delta). Missing item: silent no-op.
     */
    override suspend fun deleteItem(listId: String, itemId: String) {
        val uid = currentUid.currentUid()
        val itemRef = itemsCollection(uid, listId).document(itemId)
        val listRef = listDoc(uid, listId)
        runItemCounterTransaction(itemRef) { transaction, snapshot ->
            if (snapshot.exists()) {
                val wasActive = snapshot.get(FirebaseSchema.Fields.DELETED_AT) == null
                val wasCompleted = snapshot.getBoolean(FirebaseSchema.Fields.IS_COMPLETED) ?: false
                transaction.delete(itemRef)
                if (wasActive) {
                    transaction.update(listRef, counterDelta(total = -1L, completed = if (wasCompleted) -1L else null))
                }
            }
        }
    }

    /**
     * Tombstones every currently active-and-completed item in [listId], chunked under
     * Firestore's 500-writes-per-batch cap ([clearCompletedChunkSize] item writes + one
     * trailing list-counter write per batch).
     *
     * Resumable/idempotent by construction: each iteration re-queries "active AND
     * completed" from the server, so a retry after a partial failure (some chunks
     * committed, a later one failed) only ever picks up items that are still active and
     * completed - already-tombstoned items from earlier chunks (this attempt or a prior
     * one) fall out of the query and are never touched twice. Each chunk's item
     * tombstones and its list-counter decrement commit together in one atomic
     * [com.google.firebase.firestore.WriteBatch]; the multi-chunk sequence as a whole is
     * *not* one atomic unit (Firestore has no such primitive at this scale) - that is
     * exactly what the re-query-driven resumability is for.
     */
    override suspend fun clearCompleted(listId: String) {
        val uid = currentUid.currentUid()
        val collection = itemsCollection(uid, listId)
        val listRef = listDoc(uid, listId)
        var iterations = 0
        while (true) {
            iterations++
            check(iterations <= MAX_CLEAR_COMPLETED_ITERATIONS) {
                "clearCompleted did not converge after $MAX_CLEAR_COMPLETED_ITERATIONS chunks for list $listId"
            }
            val page = runFirestoreQuery {
                collection
                    .whereEqualTo(FirebaseSchema.Fields.IS_COMPLETED, true)
                    .whereEqualTo(FirebaseSchema.Fields.DELETED_AT, null)
                    .limit(clearCompletedChunkSize.toLong())
                    .get()
            }
            if (page.isEmpty) break
            val count = page.size().toLong()
            runFirestoreCall {
                val batch = firestore.batch()
                page.documents.forEach { doc ->
                    batch.update(
                        doc.reference,
                        FirestoreValueCodec.encode(
                            FieldPatch(
                                mapOf(
                                    FirebaseSchema.Fields.DELETED_AT to FirebaseValue.PendingServerTimestamp,
                                    FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                                )
                            ),
                        ),
                    )
                }
                batch.update(listRef, counterDelta(total = -count, completed = -count))
                batch.commit()
            }
        }
    }

    private fun counterDelta(total: Long?, completed: Long?): Map<String, Any> = buildMap {
        if (total != null) put(FirebaseSchema.Fields.TOTAL_ITEMS, FieldValue.increment(total))
        if (completed != null) put(FirebaseSchema.Fields.COMPLETED_ITEMS, FieldValue.increment(completed))
    }

    private suspend fun runFirestoreCall(call: () -> Task<Void>) {
        try {
            call().awaitTaskResult()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw throwable.toListRepositoryException()
        }
    }

    private suspend fun runFirestoreQuery(call: () -> Task<QuerySnapshot>): QuerySnapshot {
        try {
            return call().awaitTaskResult()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw throwable.toListRepositoryException()
        }
    }

    /** Hardened counter-direction Rules may reject an optimistic stale delta before
     * the SDK classifies it as contention. Retry only when a server reread proves the
     * exact item's existence/completion/tombstone state read by this attempt changed. Stable authorization/schema denials remain
     * terminal, and the full item+counter transaction is recomputed, never replayed. */
    private suspend fun runItemCounterTransaction(
        itemRef: DocumentReference,
        block: (Transaction, DocumentSnapshot) -> Unit,
    ) {
        var retries = 0
        while (true) {
            var read: DocumentSnapshot? = null
            try {
                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(itemRef)
                    read = snapshot
                    block(transaction, snapshot)
                }.awaitTaskResult()
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val attempted = read
                if (throwable is FirebaseFirestoreException &&
                    throwable.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                    attempted != null && retries < 3
                ) {
                    val current = try {
                        itemRef.get(Source.SERVER).awaitTaskResult()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) { null }
                    if (current != null && (
                        current.exists() != attempted.exists() ||
                        current.get(FirebaseSchema.Fields.IS_COMPLETED) != attempted.get(FirebaseSchema.Fields.IS_COMPLETED) ||
                        current.get(FirebaseSchema.Fields.DELETED_AT) != attempted.get(FirebaseSchema.Fields.DELETED_AT)
                    )) {
                        retries++
                        continue
                    }
                }
                throw throwable.toListRepositoryException()
            }
        }
    }

    private companion object {
        /** Defensive-only: guards against a future regression turning this into an infinite loop. */
        const val MAX_CLEAR_COMPLETED_ITERATIONS = 100_000
    }
}

/** Firestore's hard per-`WriteBatch` operation cap (SDK-documented, not tunable). */
internal const val MAX_BATCH_WRITES = 500

/** See [AndroidFirebaseItemRepository.clearCompletedChunkSize]'s KDoc for why this is well under [MAX_BATCH_WRITES]. */
internal const val DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE = 400

/**
 * The pure invariant behind FB-204's `>500-item chunk strategy`: a `clearCompleted` batch
 * always pairs up to [chunkSize] item-tombstone writes with exactly one trailing
 * list-counter-update write in the same [com.google.firebase.firestore.WriteBatch], so
 * `chunkSize + 1` must never exceed Firestore's hard per-batch write cap. Extracted as a
 * standalone, [FirebaseFirestore]-free function specifically so it is unit-testable
 * without constructing an [AndroidFirebaseItemRepository] (which needs a live
 * `FirebaseApp`) - see `AndroidFirebaseItemRepositoryChunkSizeTest`.
 */
internal fun requireValidClearCompletedChunkSize(chunkSize: Int, maxBatchWrites: Int = MAX_BATCH_WRITES) {
    require(chunkSize in 1..maxBatchWrites - 1) {
        "clearCompletedChunkSize must leave room for the trailing counter-update write " +
            "in the same batch (Firestore's hard cap is $maxBatchWrites writes/batch), " +
            "got $chunkSize"
    }
}

/**
 * Suspends until a [Task] of any result type completes, rethrowing its exception so the
 * single error mapper in `com.fluxit.firebase.list`'s `FirestoreErrorMapping.kt` decides
 * what the failure means. A generic sibling of that package's `Task<Void>.awaitCompletion()`
 * - needed here because `runTransaction`/query `.get()` return `Task<T>` for a non-`Void`
 * `T` (the transaction's result, or a [QuerySnapshot]), which that `Void`-only helper
 * cannot express. Kept local rather than widening the existing helper's visibility, to
 * avoid touching FB-202's file for an FB-204 concern.
 */
private suspend fun <T> Task<T>.awaitTaskResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val exception = task.exception
        when {
            exception != null -> continuation.resumeWithException(exception)
            task.isCanceled -> continuation.cancel(CancellationException("Firestore task cancelled"))
            else -> continuation.resume(task.result)
        }
    }
}
