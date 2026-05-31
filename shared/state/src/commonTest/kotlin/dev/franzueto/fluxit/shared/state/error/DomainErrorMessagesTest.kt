package dev.franzueto.fluxit.shared.state.error

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.port.SchedulerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DomainErrorMessagesTest {
    @Test
    fun validation_empty_names_the_field() {
        assertEquals("Title can't be empty.", DomainError.Validation("Title", ValidationError.Empty).userMessage)
    }

    @Test
    fun validation_too_long_includes_the_cap() {
        assertEquals(
            "Name is too long (max 50).",
            DomainError.Validation("Name", ValidationError.TooLong(max = 50)).userMessage,
        )
    }

    @Test
    fun validation_invalid_format_and_not_in_future_have_copy() {
        assertTrue(DomainError.Validation("X", ValidationError.InvalidFormat).userMessage.isNotBlank())
        assertTrue(DomainError.Validation("X", ValidationError.NotInFuture).userMessage.isNotBlank())
    }

    @Test
    fun not_found_and_storage_failure_are_generic() {
        assertEquals("We couldn't find that item.", DomainError.NotFound("List", "abc").userMessage)
        assertEquals(
            "Something went wrong saving your changes.",
            DomainError.StorageFailure(cause = null).userMessage,
        )
    }

    @Test
    fun conflict_passes_its_message_through() {
        assertEquals("That name is already taken.", DomainError.Conflict("That name is already taken.").userMessage)
    }

    @Test
    fun scheduler_permission_denied_asks_to_allow_notifications() {
        assertEquals(
            "Allow notifications to set reminders.",
            DomainError.SchedulerFailure(SchedulerError.PermissionDenied).userMessage,
        )
        assertTrue(DomainError.SchedulerFailure(SchedulerError.SystemBusy).userMessage.isNotBlank())
        assertTrue(DomainError.SchedulerFailure(SchedulerError.Unknown(null)).userMessage.isNotBlank())
    }

    @Test
    fun capture_permission_denied_asks_to_allow_camera() {
        assertEquals(
            "Allow camera access to add photos.",
            DomainError.CaptureFailure(CaptureError.PermissionDenied).userMessage,
        )
        assertTrue(DomainError.CaptureFailure(CaptureError.UserCancelled).userMessage.isNotBlank())
        assertTrue(DomainError.CaptureFailure(CaptureError.Unknown(null)).userMessage.isNotBlank())
    }
}
