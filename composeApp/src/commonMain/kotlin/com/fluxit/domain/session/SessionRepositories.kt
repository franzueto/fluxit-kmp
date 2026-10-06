package com.fluxit.domain.session

import com.fluxit.domain.*
import com.fluxit.data.*
import kotlinx.coroutines.flow.Flow


class SessionListRepository(private val delegate: ListRepository, private val work: SessionWork) : ListRepository {
    override fun observeListSummaries(): Flow<List<FluxListSummary>> = work.observe { delegate.observeListSummaries() }
    override fun observeListSummariesSnapshot(): Flow<RepositorySnapshot<List<FluxListSummary>>> = work.observe { delegate.observeListSummariesSnapshot() }
    override fun observeList(listId: String): Flow<FluxList?> = work.observe { delegate.observeList(listId) }
    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String = work.run { delegate.createList(name, icon, color) }
    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor): Unit = work.run { delegate.updateList(listId, name, icon, color) }
    override suspend fun softDeleteList(listId: String): Unit = work.run { delegate.softDeleteList(listId) }
    override suspend fun restoreList(listId: String): Unit = work.run { delegate.restoreList(listId) }
    override suspend fun purgeExpired(): Unit = work.run { delegate.purgeExpired() }
}


class SessionItemRepository(private val delegate: ItemRepository, private val work: SessionWork) : ItemRepository {
    override fun observeItems(listId: String): Flow<List<FluxItem>> = work.observe { delegate.observeItems(listId) }
    override fun observeItemsSnapshot(listId: String): Flow<RepositorySnapshot<List<FluxItem>>> = work.observe { delegate.observeItemsSnapshot(listId) }
    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> = work.observe { delegate.observeItem(listId, itemId) }
    override suspend fun addItem(listId: String, title: String): Unit = work.run { delegate.addItem(listId, title) }
    override suspend fun updateItem(listId: String, itemId: String, title: String, description: String?): Unit = work.run { delegate.updateItem(listId, itemId, title, description) }
    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean): Unit = work.run { delegate.setCompleted(listId, itemId, completed) }
    override suspend fun setPhotoRef(listId: String, itemId: String, photoRef: String?): Unit = work.run { delegate.setPhotoRef(listId, itemId, photoRef) }
    override suspend fun softDeleteItem(listId: String, itemId: String): Unit = work.run { delegate.softDeleteItem(listId, itemId) }
    override suspend fun restoreItem(listId: String, itemId: String): Unit = work.run { delegate.restoreItem(listId, itemId) }
    override suspend fun deleteItem(listId: String, itemId: String): Unit = work.run { delegate.deleteItem(listId, itemId) }
    override suspend fun clearCompleted(listId: String): Unit = work.run { delegate.clearCompleted(listId) }
}


class SessionPhotoStorage(private val delegate: PhotoStorage, private val work: SessionWork) : PhotoStorage {
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String = work.run { delegate.uploadPhoto(itemId, bytes) }
    override suspend fun loadPhoto(photoRef: String): PhotoContent? = work.run { delegate.loadPhoto(photoRef) }
    override suspend fun deletePhoto(photoRef: String): Unit = work.run { delegate.deletePhoto(photoRef) }
}
