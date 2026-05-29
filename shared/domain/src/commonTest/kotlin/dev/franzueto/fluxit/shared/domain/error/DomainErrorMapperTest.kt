package dev.franzueto.fluxit.shared.domain.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DomainErrorMapperTest {
    @Test
    fun not_found_uses_supplied_entity_label() {
        val mapped = DataError.NotFound(id = "abc").toDomain(entity = "List")
        assertEquals(DomainError.NotFound(entity = "List", id = "abc"), mapped)
    }

    @Test
    fun not_found_defaults_entity_to_unknown() {
        val mapped = DataError.NotFound(id = "abc").toDomain()
        assertEquals(DomainError.NotFound(entity = "unknown", id = "abc"), mapped)
    }

    @Test
    fun conflict_passes_reason_as_message() {
        val mapped = DataError.Conflict(reason = "FK violation").toDomain()
        assertEquals(DomainError.Conflict(message = "FK violation"), mapped)
    }

    @Test
    fun storage_preserves_cause() {
        val cause = RuntimeException("disk full")
        val mapped = DataError.Storage(cause = cause).toDomain()
        val storage = assertIs<DomainError.StorageFailure>(mapped)
        assertSame(cause, storage.cause)
    }

    @Test
    fun validation_maps_to_conflict_not_to_domain_validation() {
        // DataError.Validation = storage-side constraint violation; should
        // NOT surface as DomainError.Validation (which is reserved for
        // use-case-level input validators).
        val mapped = DataError.Validation(field = "title", reason = "empty").toDomain()
        val conflict = assertIs<DomainError.Conflict>(mapped)
        assertTrue("title" in conflict.message)
        assertTrue("empty" in conflict.message)
    }

    @Test
    fun unknown_wraps_cause_as_storage_failure() {
        val cause = IllegalStateException("???")
        val mapped = DataError.Unknown(cause = cause).toDomain()
        val storage = assertIs<DomainError.StorageFailure>(mapped)
        assertSame(cause, storage.cause)
    }
}
