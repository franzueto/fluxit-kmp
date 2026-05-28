package dev.franzueto.fluxit.shared.domain.rule

import dev.franzueto.fluxit.shared.domain.rule.SortOrderArithmetic.REBALANCE_EPSILON
import dev.franzueto.fluxit.shared.domain.rule.SortOrderArithmetic.SEED_SORT_ORDER
import dev.franzueto.fluxit.shared.domain.rule.SortOrderArithmetic.between
import dev.franzueto.fluxit.shared.domain.rule.SortOrderArithmetic.needsCompaction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SortOrderArithmeticTest {
    // ── between ──────────────────────────────────────────────────────────

    @Test
    fun between_empty_brackets_returns_seed() {
        assertEquals(SEED_SORT_ORDER, between(prev = null, next = null))
    }

    @Test
    fun between_null_prev_inserts_before_next() {
        assertEquals(4.0, between(prev = null, next = 5.0))
    }

    @Test
    fun between_null_next_inserts_after_prev() {
        assertEquals(6.0, between(prev = 5.0, next = null))
    }

    @Test
    fun between_bracketed_returns_midpoint() {
        assertEquals(1.5, between(prev = 1.0, next = 2.0))
        assertEquals(2.5, between(prev = 2.0, next = 3.0))
    }

    @Test
    fun between_tight_brackets_still_returns_midpoint() {
        // Caller is responsible for checking needsCompaction —
        // between() always returns the midpoint regardless of gap.
        val mid = between(prev = 1.0, next = 1.0 + REBALANCE_EPSILON / 2)
        assertTrue(mid in 1.0..(1.0 + REBALANCE_EPSILON))
    }

    // ── needsCompaction ─────────────────────────────────────────────────

    @Test
    fun needs_compaction_empty_list_is_false() {
        assertFalse(needsCompaction(emptyList()))
    }

    @Test
    fun needs_compaction_single_element_is_false() {
        assertFalse(needsCompaction(listOf(1.0)))
    }

    @Test
    fun needs_compaction_well_spaced_list_is_false() {
        assertFalse(needsCompaction(listOf(1.0, 2.0, 3.0, 100.0)))
    }

    @Test
    fun needs_compaction_triggers_on_first_tight_gap() {
        // Below-threshold gap anywhere in the list triggers compaction.
        assertTrue(needsCompaction(listOf(1.0, 1.0 + REBALANCE_EPSILON / 2, 2.0)))
        assertTrue(needsCompaction(listOf(1.0, 2.0, 2.0 + REBALANCE_EPSILON / 2)))
    }

    @Test
    fun needs_compaction_threshold_exclusive() {
        // Gap exactly at threshold does NOT trigger (< not <=).
        assertFalse(needsCompaction(listOf(1.0, 1.0 + REBALANCE_EPSILON)))
    }

    // ── stress / property test ──────────────────────────────────────────

    @Test
    fun property_random_inserts_midpoints_stay_strictly_inside_brackets() {
        // Phase 04 §8 spec property: 1000 random inserts via between()
        // produce midpoints strictly inside their brackets. With random
        // bracket selection across an initially well-spaced list,
        // gaps stay above REBALANCE_EPSILON, so compaction does not
        // trigger here — that's exercised by the targeted test below.
        val rng = Random(seed = 0xC0FFEE)
        val orders = mutableListOf(1.0, 2.0, 3.0)
        repeat(1_000) {
            val i = rng.nextInt(orders.size - 1)
            val prev = orders[i]
            val next = orders[i + 1]
            val mid = between(prev = prev, next = next)
            assertTrue(
                mid > prev && mid < next,
                "midpoint must be strictly inside bracket — prev=$prev, mid=$mid, next=$next",
            )
            orders.add(i + 1, mid)
        }
        // Confirm the resulting list is still sorted (sanity that the
        // insert position aligned with the bracket choice).
        for (k in 1 until orders.size) {
            assertTrue(orders[k] >= orders[k - 1], "orders out of sequence at $k")
        }
    }

    @Test
    fun repeated_inserts_into_same_bracket_eventually_trigger_compaction() {
        // Concentrating inserts on the smallest gap drives the bracket
        // width below REBALANCE_EPSILON in ~30 doublings. Targeted
        // exercise of the compaction path the random property test
        // above intentionally doesn't hit.
        var orders = mutableListOf(1.0, 2.0)
        var compactions = 0
        repeat(60) {
            // Always insert between the two tightest neighbours.
            var tightI = 0
            var tightGap = Double.MAX_VALUE
            for (k in 1 until orders.size) {
                val gap = orders[k] - orders[k - 1]
                if (gap < tightGap) {
                    tightGap = gap
                    tightI = k - 1
                }
            }
            val mid = between(orders[tightI], orders[tightI + 1])
            orders.add(tightI + 1, mid)
            if (needsCompaction(orders)) {
                orders = MutableList(orders.size) { idx -> (idx + 1).toDouble() }
                compactions++
            }
        }
        assertTrue(compactions >= 1, "expected at least one compaction; got $compactions")
        // After compaction the list is back to integer spacing, so
        // needsCompaction returns false immediately afterwards.
        assertFalse(needsCompaction(orders), "post-compaction list must not need compaction")
    }
}
