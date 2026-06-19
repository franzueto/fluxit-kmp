package dev.franzueto.fluxit.feature.lists

import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the pure dashboard-row formatters in [DashboardComponents]
 * (`subtitleFor` / `relativeTime`). Deferred from Slice 5 (plan/07 §4 note);
 * they cover the §3/§12 subtitle priority and the relative-time buckets.
 */
class SubtitleFormatTest {
    private val now = Instant.parse("2026-06-02T12:00:00Z")

    private fun summary(
        total: Int,
        completed: Int,
        lastActivityAt: Instant = now,
    ): ListSummary =
        ListSummary(
            id = ListId("l1"),
            name = "Groceries",
            icon = FluxItIconRef.CART,
            color = ColorToken.PRIMARY_BLUE,
            isStarred = false,
            totalItems = total,
            completedItems = completed,
            lastActivityAt = lastActivityAt,
        )

    @Test
    fun empty_list_reads_no_items_yet() {
        assertEquals("No items yet", subtitleFor(summary(total = 0, completed = 0), now))
    }

    @Test
    fun partially_completed_list_shows_completion_percent() {
        // 2 of 4 completed → "50% completed".
        assertEquals("4 items · 50% completed", subtitleFor(summary(total = 4, completed = 2), now))
    }

    @Test
    fun fully_completed_list_falls_through_to_relative_time() {
        // completed == total is outside 1 until total, so it is not a percent.
        assertEquals(
            "3 items · Last updated just now",
            subtitleFor(summary(total = 3, completed = 3), now),
        )
    }

    @Test
    fun zero_completed_list_falls_through_to_relative_time() {
        assertEquals(
            "2 items · Last updated 5m ago",
            subtitleFor(summary(total = 2, completed = 0, lastActivityAt = now - 5.minutes), now),
        )
    }

    @Test
    fun relative_time_buckets() {
        assertEquals("just now", relativeTime(now - 30.seconds, now))
        assertEquals("5m ago", relativeTime(now - 5.minutes, now))
        assertEquals("3h ago", relativeTime(now - 3.hours, now))
        assertEquals("2d ago", relativeTime(now - 2.days, now))
        assertEquals("3w ago", relativeTime(now - 21.days, now))
    }
}
