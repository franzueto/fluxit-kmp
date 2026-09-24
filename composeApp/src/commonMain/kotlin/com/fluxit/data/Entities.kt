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
    // Kotlin-side name follows the domain rename (FB-301: photoPath -> photoRef); the on-disk
    // column name is pinned to avoid a Room schema-version bump/migration for this legacy,
    // soon-to-be-removed local persistence layer (Room bindings are already behind a dev flag
    // per FB-207 and are being replaced by Firebase repositories).
    @ColumnInfo(name = "photoPath") val photoRef: String?,
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
