package com.fluxit.data

import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

@OptIn(ExperimentalUuidApi::class)
internal fun newId(): String = Uuid.random().toString()

private fun ListEntity.toDomain() = FluxList(
    id = id,
    name = name,
    icon = ListIcon.valueOf(icon),
    color = ListColor.valueOf(color),
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ItemEntity.toDomain() = FluxItem(
    id = id,
    listId = listId,
    title = title,
    description = description,
    isCompleted = isCompleted,
    photoPath = photoPath,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

class RoomListRepository(private val db: FluxItDatabase) : ListRepository {
    private val dao get() = db.listDao()

    override fun observeListSummaries(): Flow<List<FluxListSummary>> =
        dao.observeListsWithCounts().map { rows ->
            rows.map { row ->
                FluxListSummary(
                    list = FluxList(
                        id = row.id,
                        name = row.name,
                        icon = ListIcon.valueOf(row.icon),
                        color = ListColor.valueOf(row.color),
                        sortOrder = row.sortOrder,
                        createdAt = row.createdAt,
                        updatedAt = row.updatedAt,
                    ),
                    totalItems = row.totalItems,
                    completedItems = row.completedItems,
                )
            }
        }

    override fun observeList(listId: String): Flow<FluxList?> =
        dao.observeList(listId).map { it?.toDomain() }

    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String {
        val now = nowMillis()
        val id = newId()
        dao.insert(
            ListEntity(
                id = id,
                name = name,
                icon = icon.name,
                color = color.name,
                sortOrder = dao.maxSortOrder() + 1.0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        )
        return id
    }

    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) {
        val existing = dao.getById(listId) ?: return
        dao.update(
            existing.copy(name = name, icon = icon.name, color = color.name, updatedAt = nowMillis())
        )
    }

    override suspend fun softDeleteList(listId: String) = dao.setDeletedAt(listId, nowMillis())

    override suspend fun restoreList(listId: String) = dao.setDeletedAt(listId, null)

    override suspend fun purgeExpired() {
        val cutoff = nowMillis() - 60_000L
        dao.purgeDeleted(cutoff)
        db.itemDao().purgeDeleted(cutoff)
    }
}

class RoomItemRepository(private val db: FluxItDatabase) : ItemRepository {
    private val dao get() = db.itemDao()

    override fun observeItems(listId: String): Flow<List<FluxItem>> =
        dao.observeItems(listId).map { items -> items.map { it.toDomain() } }

    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> =
        dao.observeItem(listId, itemId).map { it?.toDomain() }

    override suspend fun addItem(listId: String, title: String) {
        val now = nowMillis()
        dao.insert(
            ItemEntity(
                id = newId(),
                listId = listId,
                title = title,
                description = null,
                isCompleted = false,
                photoPath = null,
                sortOrder = dao.maxSortOrder(listId) + 1.0,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        )
    }

    override suspend fun updateItem(listId: String, itemId: String, title: String, description: String?) =
        dao.updateContent(listId, itemId, title, description, nowMillis())

    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean) =
        dao.setCompleted(listId, itemId, completed, nowMillis())

    override suspend fun setPhotoPath(listId: String, itemId: String, photoPath: String?) =
        dao.setPhotoPath(listId, itemId, photoPath, nowMillis())

    override suspend fun softDeleteItem(listId: String, itemId: String) = dao.setDeletedAt(listId, itemId, nowMillis())

    override suspend fun restoreItem(listId: String, itemId: String) = dao.setDeletedAt(listId, itemId, null)

    override suspend fun deleteItem(listId: String, itemId: String) = dao.delete(listId, itemId)

    override suspend fun clearCompleted(listId: String) = dao.softDeleteCompleted(listId, nowMillis())
}
