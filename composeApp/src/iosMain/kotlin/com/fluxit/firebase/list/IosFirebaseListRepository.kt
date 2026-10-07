@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fluxit.firebase.list

import com.fluxit.data.remote.ContractResult
import com.fluxit.data.remote.FieldPatch
import com.fluxit.data.remote.FirebaseDocumentDto
import com.fluxit.data.remote.FirebaseDocumentMapper
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import com.fluxit.domain.RepositorySnapshot
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.time

/**
 * iOS [ListRepository] backed by the official Firebase Apple Firestore SDK,
 * against the same `users/{uid}/lists/{listId}` path `AndroidFirebaseListRepository`
 * uses.
 *
 * Deliberately the same shape as `AndroidFirebaseListRepository` so the two platforms
 * cannot drift, but not the same mechanism: Firebase code on iOS lives in Swift, so the Firestore SDK itself is
 * untouchable from Kotlin here, so every Firebase call goes through the
 * Swift-implemented [IosFirestoreListBridge] (`iosApp/iosApp/FirebaseListBridge.swift`).
 *
 * Design notes, each one a deliberate mirror of the Android adapter:
 *
 * - No Firebase SDK type appears in [ListRepository]'s signatures; the SDK is reached
 *   only from Swift. Nothing in this file is imported from `commonMain`/`commonTest`.
 * - [currentUid] is resolved inside each `callbackFlow` producer block and at the start
 *   of each `suspend` function body - never in a constructor or a `val` initializer -
 *   for the same reason `AndroidFirebaseListRepository` documents (the session can change).
 * - Every listener is registered by the bridge and removed from `awaitClose`, so
 *   cancelling a collector genuinely releases the underlying Firestore listener
 * (mirrors [com.fluxit.firebase.auth.IosAuthRepository]'s `session` flow and the
 *   `callbackFlow`/`awaitClose { registration.remove() }` shape).
 * - Ordering and tombstone filtering are entirely the mapper's job: every
 *   [IosFirestoreListDocument] is turned into a [FirebaseDocumentDto] and passed through
 *   [FirebaseDocumentMapper.list] (which drops tombstoned/malformed documents), and the
 *   survivors are sorted with [FirebaseDocumentMapper.listOrdering]. Exactly like
 *   `AndroidFirebaseListRepository`, no server-side `orderBy` is used, for the same
 *   pending-`serverTimestamp()` sort-instability reason.
 * - Every mutation is a field-scoped [FieldPatch] applied through
 *   [IosFirestoreListBridge.updateListFields] (Firestore's `updateData(_:)`, which only
 *   ever touches the keys present in the map) - never `setData(_:)` - per the field-level last-write-wins policy.
 *   The one exception is [createList]: a brand-new document has no
 *   existing state to conflict with, so writing its full initial field set with
 *   `setData(_:)` does not contradict the field-level last-write-wins policy.
 */
class IosFirebaseListRepository internal constructor(
    private val bridgeProvider: () -> IosFirestoreListBridge,
    private val currentUid: CurrentUidProvider,
) : ListRepository {

    /**
     * Production constructor: the bridge Swift registered in
     * [IosFirestoreListBridgeRegistry], and the uid read through the already-registered
     * [com.fluxit.firebase.auth.IosAuthBridge]. Both resolved lazily, per call, not at
     * construction time - see [IosFirestoreListBridgeRegistry.requireBridge]'s KDoc for
     * why.
     */
    constructor() : this(IosFirestoreListBridgeRegistry::requireBridge, IosAuthBridgeCurrentUidProvider())

    override fun observeListSummaries(): Flow<List<FluxListSummary>> = callbackFlow {
        val uid = currentUid.currentUid()
        val handle = bridgeProvider().observeListSummaries(
            uid = uid,
            onSnapshot = { documents ->
                val now = nowMillis()
                val summaries = documents
                    .map { it.toDto(now) }
                    .mapNotNull { dto -> (FirebaseDocumentMapper.list(dto) as? ContractResult.Value)?.value }
                    .sortedWith(FirebaseDocumentMapper.listOrdering)
                trySend(summaries)
            },
            onError = { error -> close(error.toRepositoryException()) },
        )
        awaitClose { handle.remove() }
    }

    /**
     * Real `isFromCache`/`hasPendingWrites` from the Swift-side
     * `includeMetadataChanges: true` listener behind
     * [IosFirestoreListBridge.observeListSummariesSnapshot] - see that method's KDoc for
     * why this is a separate registration from [observeListSummaries].
     */
    override fun observeListSummariesSnapshot(): Flow<RepositorySnapshot<List<FluxListSummary>>> = callbackFlow {
        val uid = currentUid.currentUid()
        val handle = bridgeProvider().observeListSummariesSnapshot(
            uid = uid,
            onSnapshot = { snapshot ->
                val now = nowMillis()
                val summaries = snapshot.documents
                    .map { it.toDto(now) }
                    .mapNotNull { dto -> (FirebaseDocumentMapper.list(dto) as? ContractResult.Value)?.value }
                    .sortedWith(FirebaseDocumentMapper.listOrdering)
                trySend(RepositorySnapshot(summaries, snapshot.isFromCache, snapshot.hasPendingWrites))
            },
            onError = { error -> close(error.toRepositoryException()) },
        )
        awaitClose { handle.remove() }
    }

    override fun observeList(listId: String): Flow<FluxList?> = callbackFlow {
        val uid = currentUid.currentUid()
        val handle = bridgeProvider().observeList(
            uid = uid,
            listId = listId,
            onSnapshot = { document ->
                if (document == null) {
                    trySend(null)
                } else {
                    val dto = document.toDto(nowMillis())
                    val list = (FirebaseDocumentMapper.list(dto) as? ContractResult.Value)?.value?.list
                    trySend(list)
                }
            },
            onError = { error -> close(error.toRepositoryException()) },
        )
        awaitClose { handle.remove() }
    }

    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String {
        val uid = currentUid.currentUid()
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
        return suspendCancellableCoroutine { continuation ->
            bridgeProvider().createList(uid, initialFields) { id, error ->
                when {
                    error != null -> continuation.resumeWithException(error.toRepositoryException())
                    id != null -> continuation.resume(id)
                    else -> continuation.resumeWithException(
                        RepositoryException(RepositoryErrorCode.UNKNOWN.toApplicationError()),
                    )
                }
            }
        }
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

    /** Field-scoped tombstone patch: touches only `deletedAt`. */
    override suspend fun softDeleteList(listId: String) {
        applyPatch(
            listId,
            FieldPatch(mapOf(FirebaseSchema.Fields.DELETED_AT to FirebaseValue.PendingServerTimestamp)),
        )
    }

    /** Field-scoped patch clearing only `deletedAt`. */
    override suspend fun restoreList(listId: String) {
        applyPatch(listId, FieldPatch(mapOf(FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null)))
    }

    /**
     * Deliberately a no-op, for exactly the reason `AndroidFirebaseListRepository`
     * documents: tombstone purge belongs to the scheduled server-side
     * cleanup job, not client-owned code. Kept only so this repository satisfies
     * [ListRepository]'s existing shape without changing it.
     */
    override suspend fun purgeExpired() = Unit

    private suspend fun applyPatch(listId: String, patch: FieldPatch) {
        val uid = currentUid.currentUid()
        suspendCancellableCoroutine<Unit> { continuation ->
            bridgeProvider().updateListFields(uid, listId, patch.fields) { error ->
                if (error != null) {
                    continuation.resumeWithException(error.toRepositoryException())
                } else {
                    continuation.resume(Unit)
                }
            }
        }
    }
}

/**
 * Captured when a local snapshot is received; used only while a server timestamp is
 * pending. Second precision (`platform.posix.time`) is sufficient for a fallback
 * ordering value - `AndroidFirebaseListRepository`'s `System.currentTimeMillis()`
 * counterpart is only ever compared against other client-fallback values or an
 * eventually-resolved server timestamp, never against itself at sub-second precision.
 */
private fun nowMillis(): Long = time(null) * 1000L

private fun IosFirestoreListDocument.toDto(clientFallbackMillis: Long): FirebaseDocumentDto =
    FirebaseDocumentDto(id = id, fields = fields, clientFallbackMillis = clientFallbackMillis)
