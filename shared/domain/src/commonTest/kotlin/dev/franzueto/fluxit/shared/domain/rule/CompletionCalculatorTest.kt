package dev.franzueto.fluxit.shared.domain.rule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionCalculatorTest {
    @Test
    fun fraction_empty_list_returns_zero() {
        assertEquals(0f, CompletionCalculator.fraction(total = 0, completed = 0))
    }

    @Test
    fun fraction_half_complete() {
        assertEquals(0.5f, CompletionCalculator.fraction(total = 20, completed = 10))
    }

    @Test
    fun fraction_fully_complete() {
        assertEquals(1f, CompletionCalculator.fraction(total = 20, completed = 20))
    }

    @Test
    fun fraction_rejects_negative_total() {
        assertFailsWith<IllegalArgumentException> {
            CompletionCalculator.fraction(total = -1, completed = 0)
        }
    }

    @Test
    fun fraction_rejects_completed_above_total() {
        assertFailsWith<IllegalArgumentException> {
            CompletionCalculator.fraction(total = 5, completed = 6)
        }
    }

    @Test
    fun display_renders_completed_over_total() {
        assertEquals("13/20", CompletionCalculator.display(total = 20, completed = 13))
        assertEquals("0/0", CompletionCalculator.display(total = 0, completed = 0))
    }

    @Test
    fun is_fully_complete_requires_at_least_one_item() {
        assertFalse(CompletionCalculator.isFullyComplete(total = 0, completed = 0))
    }

    @Test
    fun is_fully_complete_true_when_all_done() {
        assertTrue(CompletionCalculator.isFullyComplete(total = 5, completed = 5))
    }

    @Test
    fun is_fully_complete_false_when_partial() {
        assertFalse(CompletionCalculator.isFullyComplete(total = 5, completed = 4))
    }
}
