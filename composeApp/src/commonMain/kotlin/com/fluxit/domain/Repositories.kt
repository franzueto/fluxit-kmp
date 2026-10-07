package com.fluxit.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Freshness metadata accompanying a repository observation.
 *
 * Carries Firestore's real cache/pending-write signal - `DocumentSnapshot.metadata` on
 * Android (`isFromCache`, `hasPendingWrites()`), `FIRDocumentSnapshot.metadata` on iOS
 * (`isFromCache`, `hasPendingWrites`) - up to `commonMain` as two plain booleans, never the
 * SDK's own metadata type itself (no Firebase/Firestore import appears anywhere in this
 * file). [isFromCache] is true when [value] was served from the local on-device cache
 * rather than a confirmed round trip to the server (offline, or the very first frame before
 * the network listener has attached). [hasPendingWrites] is true when [value] reflects at
 * least one local write this client made that the server has not yet acknowledged.
 *
 * **Why the contract is widened rather than changed:** rather than changing
 * [ListRepository.observeListSummaries]/
 * [ItemRepository.observeItems]'s existing return type (which would force every
 * implementer - both Firestore adapters on both
 * platforms, and every test double - to change in lockstep), the repositories add *parallel*
 * `*Snapshot()` methods that return this type, each with a default body (see
 * [ListRepository.observeListSummariesSnapshot]/[ItemRepository.observeItemsSnapshot]) that
 * reports every emission as fresh (`isFromCache = false`, `hasPendingWrites = false`) by
 * re-wrapping the existing plain [Flow]. That default is what keeps every existing
 * implementer compiling unchanged.
 *
 * [com.fluxit.feature.dashboard.DashboardViewModel] and
 * [com.fluxit.feature.listdetail.ListDetailViewModel] consume this type, and fake-repository
 * tests cover every resulting UI state.
 *
 * **Real adapters:** all four real adapters override the `*Snapshot` methods
 * with the genuine SDK signal instead of inheriting this default - `AndroidFirebaseListRepository`/
 * `AndroidFirebaseItemRepository` via a second `MetadataChanges.INCLUDE`-registered
 * listener reading `QuerySnapshot.metadata`, and `IosFirebaseListRepository`/
 * `IosFirebaseItemRepository` via the Swift-implemented `IosFirestoreListBridge.
 * observeListSummariesSnapshot`/`IosFirestoreItemBridge.observeItemsSnapshot` (backed by
 * `addSnapshotListener(includeMetadataChanges: true)` reading `snapshot.metadata` in
 * `FirebaseListBridge.swift`/`FirebaseItemBridge.swift`). The default body documented
 * above (`isFromCache = false`, `hasPendingWrites = false`) remains live for
 * test doubles and any future implementer that does
 * not override it - it was never removed, only overridden by the four production
 * Firestore adapters.
 */
data class RepositorySnapshot<out T>(
    val value: T,
    val isFromCache: Boolean,
    val hasPendingWrites: Boolean,
)

interface ListRepository {
    fun observeListSummaries(): Flow<List<FluxListSummary>>

    /**
     * Same stream as [observeListSummaries], additionally carrying
     * [RepositorySnapshot] metadata - see that type's KDoc for why this is a parallel method
     * rather than a changed [observeListSummaries] signature, and for exactly how much of the
     * real Firestore signal is wired up by this task.
     */
    fun observeListSummariesSnapshot(): Flow<RepositorySnapshot<List<FluxListSummary>>> =
        observeListSummaries().map { RepositorySnapshot(it, isFromCache = false, hasPendingWrites = false) }

    fun observeList(listId: String): Flow<FluxList?>
    suspend fun createList(name: String, icon: ListIcon, color: ListColor): String
    suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor)
    suspend fun softDeleteList(listId: String)
    suspend fun restoreList(listId: String)
    suspend fun purgeExpired()
}

interface ItemRepository {
    fun observeItems(listId: String): Flow<List<FluxItem>>

    /** See [ListRepository.observeListSummariesSnapshot]'s KDoc - identical shape,
     * identical disclosed judgment call, scoped to items instead of lists. */
    fun observeItemsSnapshot(listId: String): Flow<RepositorySnapshot<List<FluxItem>>> =
        observeItems(listId).map { RepositorySnapshot(it, isFromCache = false, hasPendingWrites = false) }

    fun observeItem(listId: String, itemId: String): Flow<FluxItem?>
    suspend fun addItem(listId: String, title: String)
    suspend fun updateItem(listId: String, itemId: String, title: String, description: String?)
    suspend fun setCompleted(listId: String, itemId: String, completed: Boolean)
    suspend fun setPhotoRef(listId: String, itemId: String, photoRef: String?)
    suspend fun softDeleteItem(listId: String, itemId: String)
    suspend fun restoreItem(listId: String, itemId: String)
    suspend fun deleteItem(listId: String, itemId: String)
    suspend fun clearCompleted(listId: String)
}
