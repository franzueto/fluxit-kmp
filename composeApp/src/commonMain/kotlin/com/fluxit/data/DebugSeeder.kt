package com.fluxit.data

import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import fluxit.composeapp.generated.resources.*
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString

class DebugSeeder(
    private val lists: ListRepository,
    private val items: ItemRepository,
) {
    suspend fun seed() {
        val supermarket = lists.createList(
            getString(Res.string.sample_list_supermarket),
            ListIcon.CART,
            ListColor.ORANGE,
        )
        listOf(
            getString(Res.string.sample_item_organic_bananas),
            getString(Res.string.sample_item_whole_milk),
            getString(Res.string.sample_item_sourdough_bread),
            getString(Res.string.sample_item_greek_yogurt),
            getString(Res.string.sample_item_chicken_breasts),
            getString(Res.string.sample_item_olive_oil),
        ).forEach { items.addItem(supermarket, it) }
        // Complete a couple so the dashboard shows the "x% completed" subtitle.
        items.observeItems(supermarket).first().take(3).forEach { items.setCompleted(supermarket, it.id, true) }

        val home = lists.createList(
            getString(Res.string.sample_list_home_todo),
            ListIcon.HOME,
            ListColor.EMERALD,
        )
        listOf(
            getString(Res.string.sample_item_fix_kitchen_tap),
            getString(Res.string.sample_item_water_plants),
            getString(Res.string.sample_item_change_air_filter),
            getString(Res.string.sample_item_vacuum_living_room),
        )
            .forEach { items.addItem(home, it) }

        val trip = lists.createList(
            getString(Res.string.sample_list_trip_to_japan),
            ListIcon.TRAVEL,
            ListColor.PRIMARY_BLUE,
        )
        listOf(
            getString(Res.string.sample_item_passport),
            getString(Res.string.sample_item_jr_pass),
            getString(Res.string.sample_item_travel_adapter),
            getString(Res.string.sample_item_book_shinkansen_seats),
        )
            .forEach { items.addItem(trip, it) }

        val gifts = lists.createList(
            getString(Res.string.sample_list_gift_ideas),
            ListIcon.GIFT,
            ListColor.ROSE,
        )
        listOf(
            getString(Res.string.sample_item_birthday_card),
            getString(Res.string.sample_item_headphones_for_sam),
        ).forEach { items.addItem(gifts, it) }

        lists.createList(
            getString(Res.string.sample_list_work_q4_goals),
            ListIcon.WORK,
            ListColor.INDIGO,
        )
    }
}
