package dev.franzueto.fluxit.shared.domain.model

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrimmedNonBlankTest {
    @Test
    fun rejects_empty_input() {
        val result = TrimmedNonBlank.of("")
        assertIs<Outcome.Err<ValidationError>>(result)
        assertEquals(ValidationError.Empty, result.error)
    }

    @Test
    fun rejects_whitespace_only_input() {
        val result = TrimmedNonBlank.of("   \t\n  ")
        assertIs<Outcome.Err<ValidationError>>(result)
        assertEquals(ValidationError.Empty, result.error)
    }

    @Test
    fun trims_surrounding_whitespace_on_happy_path() {
        val result = TrimmedNonBlank.of("  Groceries  ")
        assertIs<Outcome.Ok<TrimmedNonBlank>>(result)
        assertEquals("Groceries", result.value.value)
    }

    @Test
    fun preserves_inner_whitespace() {
        val result = TrimmedNonBlank.of("  Buy   milk  ")
        assertIs<Outcome.Ok<TrimmedNonBlank>>(result)
        assertEquals("Buy   milk", result.value.value)
    }

    @Test
    fun accepts_input_at_max_length_after_trim() {
        val result = TrimmedNonBlank.of("  abcde  ", maxLen = 5)
        assertIs<Outcome.Ok<TrimmedNonBlank>>(result)
        assertEquals("abcde", result.value.value)
    }

    @Test
    fun rejects_input_over_max_length_after_trim() {
        val result = TrimmedNonBlank.of("abcdef", maxLen = 5)
        assertIs<Outcome.Err<ValidationError>>(result)
        assertEquals(ValidationError.TooLong(max = 5), result.error)
    }

    @Test
    fun null_max_len_disables_length_cap() {
        // A 10_000-char string should pass when maxLen is null.
        val long = "a".repeat(10_000)
        val result = TrimmedNonBlank.of(long, maxLen = null)
        assertIs<Outcome.Ok<TrimmedNonBlank>>(result)
        assertTrue(result.value.value.length == 10_000)
    }
}
