package dev.franzueto.fluxit.feature.createlist

import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.state.store.CreateListState
import dev.franzueto.fluxit.shared.state.store.NameValidation
import dev.franzueto.fluxit.shared.state.store.PendingReminder
import dev.franzueto.fluxit.shared.state.store.Submission
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure-logic coverage for the screen's formatters (plan/09 §15 — snapshot tests deferred to v2). */
class CreateListFormattersTest {
    @Test
    fun name_error_is_hidden_until_validation_is_visible() {
        assertNull(nameErrorMessage(CreateListState(validation = NameValidation.Empty)))
        assertNull(
            nameErrorMessage(
                CreateListState(validation = NameValidation.Valid, validationVisible = true),
            ),
        )
        assertEquals(
            "Give your list a name.",
            nameErrorMessage(CreateListState(validation = NameValidation.Empty, validationVisible = true)),
        )
        assertEquals(
            "Keep the name under 60 characters.",
            nameErrorMessage(CreateListState(validation = NameValidation.TooLong, validationVisible = true)),
        )
    }

    @Test
    fun submit_label_reflects_mode_and_inflight_submission() {
        assertEquals("Create List", submitLabel(CreateListState()))
        assertEquals("Save", submitLabel(CreateListState(editing = true)))
        assertEquals("Creating…", submitLabel(CreateListState(submission = Submission.Submitting)))
        assertEquals(
            "Saving…",
            submitLabel(CreateListState(editing = true, submission = Submission.Submitting)),
        )
    }

    @Test
    fun pickable_icons_drop_the_more_glyph() {
        val picked = pickableIcons(FluxItIconRef.entries.toList())
        assertEquals(FluxItIconRef.entries.size - 1, picked.size)
        assertEquals(false, FluxItIconRef.MORE in picked)
    }

    @Test
    fun reminder_subtitle_tracks_flag_and_configured_reminder() {
        assertEquals("Coming soon", reminderSubtitle(CreateListState()))
        assertEquals("None", reminderSubtitle(CreateListState(reminderEditorEnabled = true)))
        assertEquals(
            "Scheduled",
            reminderSubtitle(
                CreateListState(
                    reminderEditorEnabled = true,
                    reminder = PendingReminder(Instant.fromEpochSeconds(1_800_000_000)),
                ),
            ),
        )
    }
}
