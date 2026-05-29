package dev.franzueto.fluxit.shared.domain.rule

import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrenceCalculatorTest {
    // ── helpers ──────────────────────────────────────────────────────────

    private val london = TimeZone.of("Europe/London")
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private val apia = TimeZone.of("Pacific/Apia")
    private val utc = TimeZone.UTC

    private fun localInstant(
        tz: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Instant = LocalDateTime(year, month, day, hour, minute).toInstant(tz)

    private fun assertLocal(
        actual: Instant,
        tz: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ) {
        val local = actual.toLocalDateTime(tz)
        assertEquals(
            LocalDateTime(year, month, day, hour, minute),
            local,
            "expected $year-$month-$day $hour:$minute in $tz, got $local",
        )
    }

    // ── None ─────────────────────────────────────────────────────────────

    @Test
    fun none_returns_null() {
        assertNull(
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.None,
                after = localInstant(utc, 2026, 3, 15, 9),
                tz = utc,
            ),
        )
    }

    // ── Daily ────────────────────────────────────────────────────────────

    @Test
    fun daily_advances_one_day_at_same_wall_clock_time_in_utc() {
        val after = localInstant(utc, 2026, 3, 15, 9, 30)
        val next = RecurrenceCalculator.nextFireAfter(RecurrenceRule.Daily, after, utc)!!
        assertLocal(next, utc, 2026, 3, 16, 9, 30)
    }

    @Test
    fun daily_spring_forward_skips_nonexistent_wall_clock_time_in_london() {
        // 2026-03-29 in Europe/London: clocks jump 01:00 → 02:00.
        // The wall-clock window [01:00, 02:00) doesn't exist that day.
        // A daily reminder originally at 01:30 GMT (the day before)
        // would land in the gap on the 29th — should skip to 30th.
        val after = localInstant(london, 2026, 3, 28, 1, 30)
        val next = RecurrenceCalculator.nextFireAfter(RecurrenceRule.Daily, after, london)!!
        assertLocal(next, london, 2026, 3, 30, 1, 30)
    }

    @Test
    fun daily_fall_back_uses_first_occurrence_of_ambiguous_time_in_london() {
        // 2026-10-25 in Europe/London: clocks fall back 02:00 → 01:00.
        // The wall-clock window [01:00, 02:00) occurs twice.
        // A daily reminder at 01:30 the day before should fire at the
        // FIRST occurrence on the 25th (BST 01:30, the earlier instant).
        val after = localInstant(london, 2026, 10, 24, 1, 30)
        val next = RecurrenceCalculator.nextFireAfter(RecurrenceRule.Daily, after, london)!!
        // Round-trip parses back to 01:30; the test that this is the
        // FIRST occurrence (BST, not GMT) is implicit in `after + 23h`
        // — first occurrence is BST 01:30 (= 00:30 UTC), second is GMT
        // 01:30 (= 01:30 UTC). 23h after BST 01:30 on 10-24 = 23:30
        // UTC on 10-24 → BST 00:30 on 10-25 → +1h to 01:30 = BST 01:30.
        // Strictly > after.
        assertLocal(next, london, 2026, 10, 25, 1, 30)
        assertTrue(next > after)
    }

    @Test
    fun daily_works_across_kolkata_offset_boundary() {
        // Asia/Kolkata: fixed +5:30 offset, no DST. Simple +24h regardless of UTC date.
        val after = localInstant(kolkata, 2026, 3, 15, 9, 30)
        val next = RecurrenceCalculator.nextFireAfter(RecurrenceRule.Daily, after, kolkata)!!
        assertLocal(next, kolkata, 2026, 3, 16, 9, 30)
    }

    @Test
    fun daily_result_is_strictly_after_input() {
        val after = localInstant(utc, 2026, 3, 15, 9)
        val next = RecurrenceCalculator.nextFireAfter(RecurrenceRule.Daily, after, utc)!!
        assertTrue(next > after)
    }

    // ── Weekly ───────────────────────────────────────────────────────────

    @Test
    fun weekly_single_day_advances_to_next_match() {
        // Tuesday → next Tuesday.
        // 2026-03-17 is a Tuesday.
        val after = localInstant(utc, 2026, 3, 17, 9)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Weekly(setOf(DayOfWeek.TUESDAY)),
                after = after,
                tz = utc,
            )!!
        assertLocal(next, utc, 2026, 3, 24, 9)
    }

    @Test
    fun weekly_multi_day_picks_nearest_match() {
        // After Tuesday with {Tue, Thu, Sat} → next is Thursday.
        val after = localInstant(utc, 2026, 3, 17, 9)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule =
                    RecurrenceRule.Weekly(
                        setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY),
                    ),
                after = after,
                tz = utc,
            )!!
        assertLocal(next, utc, 2026, 3, 19, 9)
    }

    @Test
    fun weekly_after_non_included_day_advances_to_next_included() {
        // After Sunday with {Wed, Fri} → next is Wednesday.
        // 2026-03-15 is a Sunday.
        val after = localInstant(utc, 2026, 3, 15, 9)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Weekly(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
                after = after,
                tz = utc,
            )!!
        assertLocal(next, utc, 2026, 3, 18, 9)
    }

    @Test
    fun weekly_empty_set_throws() {
        val after = localInstant(utc, 2026, 3, 15, 9)
        assertFailsWith<IllegalArgumentException> {
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Weekly(emptySet()),
                after = after,
                tz = utc,
            )
        }
    }

    // ── Monthly ──────────────────────────────────────────────────────────

    @Test
    fun monthly_advances_one_month_at_same_day_and_time() {
        val after = localInstant(utc, 2026, 1, 15, 9, 30)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Monthly(dayOfMonth = 15),
                after = after,
                tz = utc,
            )!!
        assertLocal(next, utc, 2026, 2, 15, 9, 30)
    }

    @Test
    fun monthly_clamps_jan_31_to_feb_28_in_common_year() {
        val after = localInstant(utc, 2026, 1, 31, 9)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Monthly(dayOfMonth = 31),
                after = after,
                tz = utc,
            )!!
        // 2026 is a common year — Feb 28.
        assertLocal(next, utc, 2026, 2, 28, 9)
    }

    @Test
    fun monthly_clamps_jan_31_to_feb_29_in_leap_year() {
        val after = localInstant(utc, 2024, 1, 31, 9)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Monthly(dayOfMonth = 31),
                after = after,
                tz = utc,
            )!!
        // 2024 is a leap year — Feb 29.
        assertLocal(next, utc, 2024, 2, 29, 9)
    }

    @Test
    fun monthly_restores_31_after_clamped_feb_28() {
        // Spec: Jan 31 → Feb 28/29 → Mar 31. The third call (after =
        // Feb 28 result) restores to Mar 31 because each call advances
        // one month from `after` and re-applies the dayOfMonth cap.
        val afterFeb = localInstant(utc, 2026, 2, 28, 9)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Monthly(dayOfMonth = 31),
                after = afterFeb,
                tz = utc,
            )!!
        assertLocal(next, utc, 2026, 3, 31, 9)
    }

    @Test
    fun monthly_year_rollover_dec_to_jan() {
        val after = localInstant(utc, 2026, 12, 15, 9)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Monthly(dayOfMonth = 15),
                after = after,
                tz = utc,
            )!!
        assertLocal(next, utc, 2027, 1, 15, 9)
    }

    @Test
    fun monthly_clamps_31_to_30_for_30_day_months() {
        // April has 30 days.
        val after = localInstant(utc, 2026, 3, 31, 9)
        val next =
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Monthly(dayOfMonth = 31),
                after = after,
                tz = utc,
            )!!
        assertLocal(next, utc, 2026, 4, 30, 9)
    }

    @Test
    fun monthly_invalid_day_throws() {
        val after = localInstant(utc, 2026, 3, 15, 9)
        assertFailsWith<IllegalArgumentException> {
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Monthly(dayOfMonth = 0),
                after = after,
                tz = utc,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RecurrenceCalculator.nextFireAfter(
                rule = RecurrenceRule.Monthly(dayOfMonth = 32),
                after = after,
                tz = utc,
            )
        }
    }

    // ── Iteration property: monotonic across many fires ──────────────────

    @Test
    fun iterated_daily_is_monotonic_across_dst_in_london() {
        // Iterate Daily through a full year in Europe/London, including
        // both DST transitions. Every successive fire must be strictly
        // after the previous one.
        var current = localInstant(london, 2026, 1, 15, 9, 30)
        repeat(365) {
            val next = RecurrenceCalculator.nextFireAfter(RecurrenceRule.Daily, current, london)!!
            assertTrue(next > current, "iteration $it not monotonic — current=$current, next=$next")
            current = next
        }
    }

    @Test
    fun iterated_monthly_clamping_walks_full_year_in_apia() {
        // Pacific/Apia: DST + 2011 dateline shift edge cases live here.
        // We start in 2026 (well after the dateline shift) and iterate
        // Monthly(31) for 24 months — each fire must be strictly after
        // the previous and land on the last day of months without 31.
        var current = localInstant(apia, 2026, 1, 31, 12)
        repeat(24) {
            val next = RecurrenceCalculator.nextFireAfter(RecurrenceRule.Monthly(31), current, apia)!!
            assertTrue(next > current, "iteration $it not monotonic — current=$current, next=$next")
            val local = next.toLocalDateTime(apia)
            // dayOfMonth must be 28, 29, 30, or 31.
            assertTrue(local.dayOfMonth in 28..31, "unexpected dayOfMonth ${local.dayOfMonth} at iteration $it")
            current = next
        }
    }

    @Test
    fun iterated_weekly_in_kolkata_is_monotonic_for_two_years() {
        var current = localInstant(kolkata, 2026, 1, 15, 9)
        val rule = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        repeat(200) {
            val next = RecurrenceCalculator.nextFireAfter(rule, current, kolkata)!!
            assertTrue(next > current)
            val dow = next.toLocalDateTime(kolkata).dayOfWeek
            assertTrue(dow == DayOfWeek.MONDAY || dow == DayOfWeek.FRIDAY)
            current = next
        }
    }
}
