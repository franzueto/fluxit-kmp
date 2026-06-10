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
    fun observeItem(itemId: String): Flow<FluxItem?>
    suspend fun addItem(listId: String, title: String)
    suspend fun updateItem(itemId: String, title: String, description: String?)
    suspend fun setCompleted(itemId: String, completed: Boolean)
    suspend fun setPhotoPath(itemId: String, photoPath: String?)
    suspend fun softDeleteItem(itemId: String)
    suspend fun restoreItem(itemId: String)
    suspend fun deleteItem(itemId: String)
    suspend fun clearCompleted(listId: String)
}
