package com.fluxit.di

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Phase 2 placeholders so the signed-in dashboard renders (and sign-out is reachable)
 * before Firestore is bridged. Reads are empty; writes fail with a retryable error.
 * Replaced by the Firestore-backed web repositories in Phase 3 (docs/web-app/PROGRESS.md).
 */
internal class PendingWebListRepository : ListRepository {
    override fun observeListSummaries(): Flow<List<FluxListSummary>> = flowOf(emptyList())
    override fun observeList(listId: String): Flow<FluxList?> = flowOf(null)
    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String = unavailable()
    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) = unavailable()
    override suspend fun softDeleteList(listId: String) = unavailable()
    override suspend fun restoreList(listId: String) = unavailable()
    override suspend fun purgeExpired() = Unit
}

/** See [PendingWebListRepository]. */
internal class PendingWebItemRepository : ItemRepository {
    override fun observeItems(listId: String): Flow<List<FluxItem>> = flowOf(emptyList())
    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> = flowOf(null)
    override suspend fun addItem(listId: String, title: String) = unavailable()
    override suspend fun updateItem(listId: String, itemId: String, title: String, description: String?) = unavailable()
    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean) = unavailable()
    override suspend fun setPhotoRef(listId: String, itemId: String, photoRef: String?) = unavailable()
    override suspend fun softDeleteItem(listId: String, itemId: String) = unavailable()
    override suspend fun restoreItem(listId: String, itemId: String) = unavailable()
    override suspend fun deleteItem(listId: String, itemId: String) = unavailable()
    override suspend fun clearCompleted(listId: String) = unavailable()
}

private fun unavailable(): Nothing = throw RepositoryException(RepositoryErrorCode.UNKNOWN.toApplicationError())
