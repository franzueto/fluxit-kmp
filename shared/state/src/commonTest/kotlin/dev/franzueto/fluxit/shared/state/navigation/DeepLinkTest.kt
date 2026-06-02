package dev.franzueto.fluxit.shared.state.navigation

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkTest {
    @Test
    fun parses_a_list_link() {
        assertEquals(DeepLink.List(ListId("abc")), DeepLink.parse("fluxit://list/abc"))
    }

    @Test
    fun parses_an_item_link() {
        assertEquals(DeepLink.Item(ItemId("xyz")), DeepLink.parse("fluxit://item/xyz"))
    }

    @Test
    fun rejects_a_foreign_scheme() {
        assertNull(DeepLink.parse("https://list/abc"))
    }

    @Test
    fun rejects_an_unknown_host() {
        assertNull(DeepLink.parse("fluxit://photo/abc"))
    }

    @Test
    fun rejects_a_missing_id() {
        assertNull(DeepLink.parse("fluxit://list/"))
        assertNull(DeepLink.parse("fluxit://list"))
    }

    @Test
    fun rejects_extra_path_segments() {
        assertNull(DeepLink.parse("fluxit://list/abc/items/def"))
    }

    @Test
    fun rejects_an_empty_string() {
        assertNull(DeepLink.parse(""))
    }
}
