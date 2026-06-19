package dev.franzueto.fluxit.feature.itemdetail

import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.state.store.ItemDetailState
import dev.franzueto.fluxit.shared.state.store.LoadState
import dev.franzueto.fluxit.shared.state.store.NameValidation
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemDetailFormattersTest {
    private fun state(
        title: String = "Milk",
        dirty: Boolean = true,
        submitting: Boolean = false,
        validation: NameValidation = NameValidation.Valid,
    ) = ItemDetailState(
        item = LoadState.Loading,
        editing = ItemPatch(title = title, subtitle = null, description = null, photoId = null),
        dirty = dirty,
        submitting = submitting,
        titleValidation = validation,
    )

    @Test
    fun save_label_reflects_submitting() {
        assertEquals("Save", saveLabel(submitting = false))
        assertEquals("Saving…", saveLabel(submitting = true))
    }

    @Test
    fun save_enabled_only_when_dirty_valid_and_idle() {
        assertTrue(saveEnabled(state()))
        assertFalse(saveEnabled(state(dirty = false)))
        assertFalse(saveEnabled(state(submitting = true)))
        assertFalse(saveEnabled(state(validation = NameValidation.Empty)))
    }

    @Test
    fun title_error_copy_matches_validation() {
        assertNull(titleErrorMessage(state(validation = NameValidation.Valid)))
        assertEquals("Give this item a name.", titleErrorMessage(state(validation = NameValidation.Empty)))
        assertEquals("Keep the name under 120 characters.", titleErrorMessage(state(validation = NameValidation.TooLong)))
    }

    @Test
    fun last_edited_label_formats_the_date() {
        // 2024-06-18T08:00:00Z — exact day depends on the runner's zone, so assert the stable prefix.
        val label = lastEditedLabel(Instant.fromEpochSeconds(1_718_700_000))
        assertTrue(label.startsWith("Last edited on "), label)
    }
}
