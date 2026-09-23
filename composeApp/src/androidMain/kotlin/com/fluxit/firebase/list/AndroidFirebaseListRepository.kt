package com.fluxit.firebase.list

import com.fluxit.data.remote.ContractResult
import com.fluxit.data.remote.FieldPatch
import com.fluxit.data.remote.FirebaseDocumentMapper
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android [ListRepository] backed by the official Firebase Android Firestore SDK
 * (FB-202), against the `users/{uid}/lists/{listId}` path from the plan's "Proposed
 * Firebase model".
 *
 * Design notes:
 *
 * - No Firebase SDK type appears in [ListRepository]'s signatures; the SDK is reached
 *   only here, in [FirestoreValueCodec] (value translation), [CurrentUidProvider] (uid
 *   resolution) and the error-mapping functions in `FirestoreErrorMapping.kt`. Nothing
 *   in this package is imported from `commonMain`/`commonTest`.
 * - [currentUid] is resolved inside each `callbackFlow` producer block and at the start
 *   of each `suspend` function body - never in a constructor or a `val` initializer -
 *   so no UID-dependent path is ever built before authentication is resolved (the
 *   standing Phase 1 constraint), including for a repository instance that outlives a
 *   sign-out/sign-in cycle.
 * - Every listener is registered inside `callbackFlow` and removed from `awaitClose`,
 *   so cancelling a collector genuinely releases the underlying Firestore listener
 *   (mirrors [com.fluxit.firebase.auth.AndroidAuthRepository]'s `session` flow).
 * - Ordering and tombstone filtering are entirely FB-201's: every document is passed
 *   through [FirebaseDocumentMapper.list] (which drops tombstoned/malformed documents)
 *   and the survivors are sorted with [FirebaseDocumentMapper.listOrdering]. No
 *   server-side `orderBy`/`whereEqualTo` is used for this, deliberately: a pending
 *   (unacknowledged) `serverTimestamp()` write can otherwise sort inconsistently in the
 *   SDK's local index until the server confirms it, which would make the visible order
 *   briefly non-deterministic. Client-side sorting over the whole (per-user, expected
 *   small) collection keeps `(createdAt, documentId)` the single source of truth for
 *   order, exactly as FB-201 defines it.
 * - Every mutation is a field-scoped [FieldPatch] applied through Firestore's `update()`
 *   (which only ever touches the keys present in the map) - never `set()` - per
 *   DEC-003d. The one exception is [createList]: a brand-new document has no existing
 *   state to conflict with, so writing its full initial field set with `set()` does not
 *   contradict DEC-003d's last-write-wins/field-merge concern, which is specifically
 *   about concurrent edits to a document that already exists.
 */
class AndroidFirebaseListRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val currentUid: CurrentUidProvider = FirebaseAuthCurrentUidProvider(),
) : ListRepository {

    private fun listsCollection(uid: String): CollectionReference =
        firestore.collection(FirebaseSchema.USERS).document(uid).collection(FirebaseSchema.LISTS)

    override fun observeListSummaries(): Flow<List<FluxListSummary>> = callbackFlow {
        val uid = currentUid.currentUid()
        val registration = listsCollection(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error.toListRepositoryException())
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            val now = System.currentTimeMillis()
            val summaries = snapshot.documents
                .mapNotNull { doc ->
                    val dto = FirestoreValueCodec.decode(doc.id, doc.data, now)
                    (FirebaseDocumentMapper.list(dto) as? ContractResult.Value)?.value
                }
                .sortedWith(FirebaseDocumentMapper.listOrdering)
            trySend(summaries)
        }
        awaitClose { registration.remove() }
    }

    override fun observeList(listId: String): Flow<FluxList?> = callbackFlow {
        val uid = currentUid.currentUid()
        val registration = listsCollection(uid).document(listId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error.toListRepositoryException())
                return@addSnapshotListener
            }
            if (snapshot == null || !snapshot.exists()) {
                trySend(null)
                return@addSnapshotListener
            }
            val now = System.currentTimeMillis()
            val dto = FirestoreValueCodec.decode(snapshot.id, snapshot.data, now)
            val list = (FirebaseDocumentMapper.list(dto) as? ContractResult.Value)?.value?.list
            trySend(list)
        }
        awaitClose { registration.remove() }
    }

    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String {
        val uid = currentUid.currentUid()
        val reference = listsCollection(uid).document()
        val initialFields = mapOf(
            FirebaseSchema.Fields.NAME to FirebaseValue.Text(name),
            FirebaseSchema.Fields.ICON to FirebaseValue.Text(icon.name),
            FirebaseSchema.Fields.COLOR to FirebaseValue.Text(color.name),
            FirebaseSchema.Fields.CREATED_AT to FirebaseValue.PendingServerTimestamp,
            FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
            FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
            FirebaseSchema.Fields.TOTAL_ITEMS to FirebaseValue.Number(0),
            FirebaseSchema.Fields.COMPLETED_ITEMS to FirebaseValue.Number(0),
            FirebaseSchema.Fields.SCHEMA_VERSION to FirebaseValue.Number(FirebaseSchema.CURRENT_VERSION),
        )
        val encoded = initialFields.mapValues { (_, value) -> FirestoreValueCodec.encode(value) }
        runFirestoreCall { reference.set(encoded) }
        return reference.id
    }

    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) {
        applyPatch(
            listId,
            FieldPatch(
                mapOf(
                    FirebaseSchema.Fields.NAME to FirebaseValue.Text(name),
                    FirebaseSchema.Fields.ICON to FirebaseValue.Text(icon.name),
                    FirebaseSchema.Fields.COLOR to FirebaseValue.Text(color.name),
                    FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                )
            ),
        )
    }

    /** DEC-003d field-scoped tombstone patch: touches only `deletedAt`. */
    override suspend fun softDeleteList(listId: String) {
        applyPatch(
            listId,
            FieldPatch(mapOf(FirebaseSchema.Fields.DELETED_AT to FirebaseValue.PendingServerTimestamp)),
        )
    }

    /** DEC-003d field-scoped patch clearing only `deletedAt`. */
    override suspend fun restoreList(listId: String) {
        applyPatch(listId, FieldPatch(mapOf(FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null)))
    }

    /**
     * Deliberately a no-op.
     *
     * DEC-003c places tombstone purge in Phase 5's scheduled server-side (Cloud
     * Functions) cleanup job, not client-owned code - the plan explicitly prefers
     * removing client-owned cleanup once server-side cleanup exists. Implementing a
     * client-side bulk delete here would duplicate that future backend job and is out
     * of FB-202's scope (list CRUD/listen/soft-delete/restore only). Kept only so this
     * repository satisfies [ListRepository]'s existing shape without changing it.
     */
    override suspend fun purgeExpired() = Unit

    private suspend fun applyPatch(listId: String, patch: FieldPatch) {
        val uid = currentUid.currentUid()
        val reference = listsCollection(uid).document(listId)
        runFirestoreCall { reference.update(FirestoreValueCodec.encode(patch)) }
    }

    private suspend fun runFirestoreCall(call: () -> Task<Void>) {
        try {
            call().awaitCompletion()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw throwable.toListRepositoryException()
        }
    }
}

/**
 * Suspends until [Task] completes, rethrowing its exception so the single error mapper
 * in `FirestoreErrorMapping.kt` - and nothing else - decides what the failure means.
 * Mirrors `FirebaseAuthGateway.kt`'s identically-purposed helper for the Auth SDK.
 */
private suspend fun Task<Void>.awaitCompletion() {
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val exception = task.exception
            when {
                exception != null -> continuation.resumeWithException(exception)
                task.isCanceled -> continuation.cancel(CancellationException("Firestore task cancelled"))
                else -> continuation.resume(Unit)
            }
        }
    }
}
