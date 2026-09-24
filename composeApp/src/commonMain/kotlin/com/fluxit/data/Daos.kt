package com.fluxit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {
    @Query(
        """
        SELECT l.id, l.name, l.icon, l.color, l.sortOrder, l.createdAt, l.updatedAt,
            (SELECT COUNT(*) FROM item_table i WHERE i.listId = l.id AND i.deletedAt IS NULL) AS totalItems,
            (SELECT COUNT(*) FROM item_table i WHERE i.listId = l.id AND i.deletedAt IS NULL AND i.isCompleted) AS completedItems
        FROM list_table l
        WHERE l.deletedAt IS NULL
        ORDER BY l.sortOrder ASC
        """
    )
    fun observeListsWithCounts(): Flow<List<ListWithCounts>>

    @Query("SELECT * FROM list_table WHERE id = :id AND deletedAt IS NULL")
    fun observeList(id: String): Flow<ListEntity?>

    @Insert
    suspend fun insert(list: ListEntity)

    @Update
    suspend fun update(list: ListEntity)

    @Query("SELECT * FROM list_table WHERE id = :id")
    suspend fun getById(id: String): ListEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM list_table")
    suspend fun maxSortOrder(): Double

    @Query("UPDATE list_table SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("DELETE FROM list_table WHERE deletedAt IS NOT NULL AND deletedAt < :olderThan")
    suspend fun purgeDeleted(olderThan: Long)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM item_table WHERE listId = :listId AND deletedAt IS NULL ORDER BY sortOrder ASC")
    fun observeItems(listId: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM item_table WHERE listId = :listId AND id = :id AND deletedAt IS NULL")
    fun observeItem(listId: String, id: String): Flow<ItemEntity?>

    @Insert
    suspend fun insert(item: ItemEntity)

    @Query("SELECT * FROM item_table WHERE id = :id")
    suspend fun getById(id: String): ItemEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM item_table WHERE listId = :listId")
    suspend fun maxSortOrder(listId: String): Double

    @Query("UPDATE item_table SET title = :title, description = :description, updatedAt = :updatedAt WHERE listId = :listId AND id = :id")
    suspend fun updateContent(listId: String, id: String, title: String, description: String?, updatedAt: Long)

    @Query("UPDATE item_table SET isCompleted = :completed, updatedAt = :updatedAt WHERE listId = :listId AND id = :id")
    suspend fun setCompleted(listId: String, id: String, completed: Boolean, updatedAt: Long)

    @Query("UPDATE item_table SET photoPath = :photoRef, updatedAt = :updatedAt WHERE listId = :listId AND id = :id")
    suspend fun setPhotoRef(listId: String, id: String, photoRef: String?, updatedAt: Long)

    @Query("UPDATE item_table SET deletedAt = :deletedAt WHERE listId = :listId AND id = :id")
    suspend fun setDeletedAt(listId: String, id: String, deletedAt: Long?)

    @Query("DELETE FROM item_table WHERE listId = :listId AND id = :id")
    suspend fun delete(listId: String, id: String)

    @Query("UPDATE item_table SET deletedAt = :deletedAt WHERE listId = :listId AND isCompleted AND deletedAt IS NULL")
    suspend fun softDeleteCompleted(listId: String, deletedAt: Long)

    @Query("DELETE FROM item_table WHERE deletedAt IS NOT NULL AND deletedAt < :olderThan")
    suspend fun purgeDeleted(olderThan: Long)
}
