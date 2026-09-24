package com.fluxit.domain

enum class ListIcon {
    CART, TRAVEL, WORK, HOME, GIFT, FOOD, FITNESS, STAR
}

enum class ListColor {
    PRIMARY_BLUE, ORANGE, EMERALD, ROSE, INDIGO, SKY
}

data class FluxList(
    val id: String,
    val name: String,
    val icon: ListIcon,
    val color: ListColor,
    val sortOrder: Double,
    val createdAt: Long,
    val updatedAt: Long,
)

data class FluxListSummary(
    val list: FluxList,
    val totalItems: Int,
    val completedItems: Int,
)

data class FluxItem(
    val id: String,
    val listId: String,
    val title: String,
    val description: String?,
    val isCompleted: Boolean,
    val photoRef: String?,
    val sortOrder: Double,
    val createdAt: Long,
    val updatedAt: Long,
)
