package com.fluxit.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "list_table")
data class ListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val color: String,
    val sortOrder: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "item_table",
    foreignKeys = [
        ForeignKey(
            entity = ListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("listId")],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val title: String,
    val description: String?,
    val isCompleted: Boolean,
    val photoPath: String?,
    val sortOrder: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class ListWithCounts(
    @ColumnInfo(name = "id") val id: String,
    val name: String,
    val icon: String,
    val color: String,
    val sortOrder: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val totalItems: Int,
    val completedItems: Int,
)
