package com.fluxit.domain

import kotlinx.coroutines.flow.Flow

interface ListRepository {
    fun observeListSummaries(): Flow<List<FluxListSummary>>
    fun observeList(listId: String): Flow<FluxList?>
    suspend fun createList(name: String, icon: ListIcon, color: ListColor): String
    suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor)
    suspend fun softDeleteList(listId: String)
    suspend fun restoreList(listId: String)
    suspend fun purgeExpired()
}

interface ItemRepository {
    fun observeItems(listId: String): Flow<List<FluxItem>>
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
