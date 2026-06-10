package com.fluxit.data

import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import kotlinx.coroutines.flow.first

class DebugSeeder(
    private val lists: ListRepository,
    private val items: ItemRepository,
) {
    suspend fun seed() {
        val supermarket = lists.createList("Supermarket", ListIcon.CART, ListColor.ORANGE)
        listOf(
            "Organic Bananas", "Whole Milk (1 gal)", "Sourdough Bread",
            "Greek Yogurt", "Chicken Breasts", "Olive Oil",
        ).forEach { items.addItem(supermarket, it) }
        // Complete a couple so the dashboard shows the "x% completed" subtitle.
        items.observeItems(supermarket).first().take(3).forEach { items.setCompleted(it.id, true) }

        val home = lists.createList("Home To-Do", ListIcon.HOME, ListColor.EMERALD)
        listOf("Fix kitchen tap", "Water plants", "Change air filter", "Vacuum living room")
            .forEach { items.addItem(home, it) }

        val trip = lists.createList("Trip to Japan", ListIcon.TRAVEL, ListColor.PRIMARY_BLUE)
        listOf("Passport", "JR Pass", "Travel adapter", "Book Shinkansen seats")
            .forEach { items.addItem(trip, it) }

        val gifts = lists.createList("Gift Ideas", ListIcon.GIFT, ListColor.ROSE)
        listOf("Birthday card", "Headphones for Sam").forEach { items.addItem(gifts, it) }

        lists.createList("Work Q4 Goals", ListIcon.WORK, ListColor.INDIGO)
    }
}
