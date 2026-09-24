package com.fluxit

import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeListRepository : ListRepository {
    data class Row(val list: FluxList, val deleted: Boolean = false)

    val rows = MutableStateFlow<List<Row>>(emptyList())
    private var counter = 0

    override fun observeListSummaries(): Flow<List<FluxListSummary>> =
        rows.map { all ->
            all.filter { !it.deleted }.map { FluxListSummary(it.list, 0, 0) }
        }

    override fun observeList(listId: String): Flow<FluxList?> =
        rows.map { all -> all.firstOrNull { it.list.id == listId && !it.deleted }?.list }

    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String {
        val id = "list-${counter++}"
        rows.value += Row(FluxList(id, name, icon, color, counter.toDouble(), 0, 0))
        return id
    }

    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) {
        rows.value = rows.value.map {
            if (it.list.id == listId) it.copy(list = it.list.copy(name = name, icon = icon, color = color))
            else it
        }
    }

    override suspend fun softDeleteList(listId: String) {
        rows.value = rows.value.map { if (it.list.id == listId) it.copy(deleted = true) else it }
    }

    override suspend fun restoreList(listId: String) {
        rows.value = rows.value.map { if (it.list.id == listId) it.copy(deleted = false) else it }
    }

    override suspend fun purgeExpired() = Unit
}

class FakeItemRepository : ItemRepository {
    data class Row(val item: FluxItem, val deleted: Boolean = false)

    val rows = MutableStateFlow<List<Row>>(emptyList())
    private var counter = 0

    override fun observeItems(listId: String): Flow<List<FluxItem>> =
        rows.map { all -> all.filter { it.item.listId == listId && !it.deleted }.map { it.item } }

    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> =
        rows.map { all -> all.firstOrNull { it.item.listId == listId && it.item.id == itemId && !it.deleted }?.item }

    override suspend fun addItem(listId: String, title: String) {
        val id = "item-${counter++}"
        rows.value += Row(FluxItem(id, listId, title, null, false, null, counter.toDouble(), 0, 0))
    }

    override suspend fun updateItem(listId: String, itemId: String, title: String, description: String?) {
        mutate(listId, itemId) { it.copy(title = title, description = description) }
    }

    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean) {
        mutate(listId, itemId) { it.copy(isCompleted = completed) }
    }

    override suspend fun setPhotoRef(listId: String, itemId: String, photoRef: String?) {
        mutate(listId, itemId) { it.copy(photoRef = photoRef) }
    }

    override suspend fun softDeleteItem(listId: String, itemId: String) {
        rows.value = rows.value.map { if (it.item.listId == listId && it.item.id == itemId) it.copy(deleted = true) else it }
    }

    override suspend fun restoreItem(listId: String, itemId: String) {
        rows.value = rows.value.map { if (it.item.listId == listId && it.item.id == itemId) it.copy(deleted = false) else it }
    }

    override suspend fun deleteItem(listId: String, itemId: String) {
        rows.value = rows.value.filter { it.item.listId != listId || it.item.id != itemId }
    }

    override suspend fun clearCompleted(listId: String) {
        rows.value = rows.value.map {
            if (it.item.listId == listId && it.item.isCompleted) it.copy(deleted = true) else it
        }
    }

    private fun mutate(listId: String, itemId: String, transform: (FluxItem) -> FluxItem) {
        rows.value = rows.value.map {
            if (it.item.listId == listId && it.item.id == itemId) it.copy(item = transform(it.item)) else it
        }
    }
}
