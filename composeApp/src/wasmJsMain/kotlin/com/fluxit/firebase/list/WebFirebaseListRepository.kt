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

/**
 * Web [ListRepository] over the Firebase JS SDK, against `users/{uid}/lists/{listId}`.
 * Ported from `IosFirebaseListRepository` (decision D2) with the same logic: the uid is
 * resolved per call, listeners are removed from `awaitClose`, tombstone filtering and
 * ordering are the shared mapper's job (no server-side `orderBy`), and every mutation
 * except [createList] is a field-scoped patch. Firestore is reached only through
 * [WebFirestoreListBridge].
 */
internal class WebFirebaseListRepository(
    private val bridgeProvider: () -> WebFirestoreListBridge,
    private val currentUid: CurrentUidProvider,
) : ListRepository {

    /** Production constructor: the JS bridge and the uid from the auth bridge, both read per call. */
    constructor() : this({ JsWebFirestoreListBridge }, WebAuthBridgeCurrentUidProvider())

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
     * Real `isFromCache`/`hasPendingWrites` from the bridge's separate
     * `includeMetadataChanges: true` listener.
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
 * Captured when a local snapshot is received; used only as the ordering fallback while a
 * server timestamp is pending.
 */
private fun nowMillis(): Long = currentTimeMillis().toLong()

private fun currentTimeMillis(): Double = js("Date.now()")

private fun WebFirestoreListDocument.toDto(clientFallbackMillis: Long): FirebaseDocumentDto =
    FirebaseDocumentDto(id = id, fields = fields, clientFallbackMillis = clientFallbackMillis)
