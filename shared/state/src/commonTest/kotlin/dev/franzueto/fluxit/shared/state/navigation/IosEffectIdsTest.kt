package dev.franzueto.fluxit.shared.state.navigation

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.state.store.ListsEffect
import dev.franzueto.fluxit.shared.state.store.RootEffect
import kotlin.test.Test
import kotlin.test.assertEquals

class IosEffectIdsTest {
    @Test
    fun lists_navigate_to_list_detail_exposes_raw_id() {
        assertEquals("list-1", ListsEffect.NavigateToListDetail(ListId("list-1")).listId())
    }

    @Test
    fun root_navigate_to_list_exposes_raw_id() {
        assertEquals("list-2", RootEffect.NavigateToList(ListId("list-2")).listId())
    }

    @Test
    fun root_navigate_to_item_exposes_raw_id() {
        assertEquals("item-9", RootEffect.NavigateToItem(ItemId("item-9")).itemId())
    }
}
