package dev.franzueto.fluxit.shared.domain.rule

import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Pure rule helper that computes the next fire instant for a
 * recurring reminder, given the last fire instant and the user's
 * time zone (Phase 04 §8). Used by `ReminderScheduler` re-arming
 * logic (Phase 04 §7's `ScheduleReminder` use case + Phase 06's
 * platform reminder ports).
 *
 * Semantics per variant (all use the wall-clock time-of-day from
 * `after` in `tz`):
 *
 * - **`None`** → `null`. No next fire; caller treats this as
 *   "one-shot reminder, don't re-arm."
 * - **`Daily`** → strictly the next calendar day at the original
 *   wall-clock time. DST spring-forward skips that day (the
 *   wall-clock time doesn't exist); DST fall-back uses the first
 *   occurrence of the ambiguous time.
 * - **`Weekly(days)`** → strictly the next calendar day whose
 *   day-of-week is in `days`, at the original wall-clock time.
 *   Same DST rules.
 * - **`Monthly(dayOfMonth)`** → the same day-of-month one month
 *   later at the original wall-clock time. **Clamp**: if the
 *   target month has fewer days (Feb, 30-day months), use the
 *   last day of the month. E.g., Jan 31 → Feb 28/29 → Mar 31.
 *   Same DST rules.
 *
 * All variants: result is **strictly greater than** `after` — no
 * infinite loops on equal instants, no zero-second re-fires.
 */
public object RecurrenceCalculator {
    /**
     * Compute the next fire instant strictly after [after].
     *
     * @return the next fire instant, or `null` for [RecurrenceRule.None].
     */
    public fun nextFireAfter(
        rule: RecurrenceRule,
        after: Instant,
        tz: TimeZone,
    ): Instant? =
        when (rule) {
            RecurrenceRule.None -> null
            RecurrenceRule.Daily ->
                findNext(after, tz) { date -> date.plus(1, DateTimeUnit.DAY) }
            is RecurrenceRule.Weekly -> nextWeekly(after, rule.daysOfWeek, tz)
            is RecurrenceRule.Monthly -> nextMonthly(after, rule.dayOfMonth, tz)
        }

    /**
     * Core search loop. Each iteration advances the candidate date
     * via [advance], constructs the candidate instant by snapping
     * to the wall-clock time-of-day from [after], and verifies the
     * round-trip (`Instant → LocalDateTime`) yields the same date
     * AND the same time-of-day — that's how we detect DST spring-
     * forward (target time doesn't exist) and skip it. Fall-back
     * ambiguity is resolved by kotlinx-datetime's default
     * `toInstant` policy (earlier of the two valid instants).
     *
     * The 400-iteration cap is a safety net against pathological
     * (rule, tz) combinations — `Monthly(31)` skipping months only
     * advances by one per iteration, so 400 covers >33 years.
     */
    private inline fun findNext(
        after: Instant,
        tz: TimeZone,
        advance: (LocalDate) -> LocalDate,
    ): Instant {
        val localAfter = after.toLocalDateTime(tz)
        val time = localAfter.time
        var date = advance(localAfter.date)
        repeat(400) {
            val candidate = LocalDateTime(date, time).toInstant(tz)
            val roundTrip = candidate.toLocalDateTime(tz)
            if (roundTrip.date == date && roundTrip.time == time && candidate > after) {
                return candidate
            }
            date = advance(date)
        }
        error(
            "RecurrenceCalculator: no valid next fire within 400 iterations " +
                "(unexpected (rule, tz) combination)",
        )
    }

    private fun nextWeekly(
        after: Instant,
        days: Set<DayOfWeek>,
        tz: TimeZone,
    ): Instant {
        require(days.isNotEmpty()) { "Weekly recurrence must specify at least one day-of-week" }
        return findNext(after, tz) { d ->
            var next = d.plus(1, DateTimeUnit.DAY)
            while (next.dayOfWeek !in days) next = next.plus(1, DateTimeUnit.DAY)
            next
        }
    }

    private fun nextMonthly(
        after: Instant,
        dayOfMonth: Int,
        tz: TimeZone,
    ): Instant {
        require(dayOfMonth in 1..31) { "dayOfMonth must be in 1..31: $dayOfMonth" }
        return findNext(after, tz) { d ->
            val nextMonthFirst = LocalDate(d.year, d.monthNumber, 1).plus(DatePeriod(months = 1))
            val followingMonthFirst = nextMonthFirst.plus(DatePeriod(months = 1))
            val daysInNextMonth = followingMonthFirst.minus(DatePeriod(days = 1)).dayOfMonth
            val effective = minOf(dayOfMonth, daysInNextMonth)
            LocalDate(nextMonthFirst.year, nextMonthFirst.monthNumber, effective)
        }
    }
}
