package dev.franzueto.fluxit.shared.data.mapper

import dev.franzueto.fluxit.shared.data.db.SelectWithCounts
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.franzueto.fluxit.shared.data.db.List as ListRow

class ListMapperTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val later = Instant.fromEpochMilliseconds(1_700_000_060_000L)

    @Test
    fun listRow_to_detail_round_trips_every_field() {
        val row =
            ListRow(
                id = "00000000-0000-4000-8000-000000000001",
                name = "Supermarket",
                icon = FluxItIconRef.CART,
                color = ColorToken.PRIMARY_BLUE,
                is_starred = true,
                sort_order = 2.0,
                created_at = now,
                updated_at = later,
                deleted_at = null,
            )

        assertEquals(
            ListDetail(
                id = ListId("00000000-0000-4000-8000-000000000001"),
                name = "Supermarket",
                icon = FluxItIconRef.CART,
                color = ColorToken.PRIMARY_BLUE,
                isStarred = true,
                createdAt = now,
                updatedAt = later,
            ),
            row.toDetail(),
        )
    }

    @Test
    fun selectWithCounts_to_summary_round_trips_counts_and_activity() {
        val row =
            SelectWithCounts(
                id = "00000000-0000-4000-8000-000000000002",
                name = "Work Q4",
                icon = FluxItIconRef.BRIEFCASE,
                color = ColorToken.ACCENT_INDIGO,
                is_starred = false,
                sort_order = 1.0,
                created_at = now,
                updated_at = now,
                total_items = 20L,
                // COALESCE(SUM(is_completed), 0) infers Double in
                // SQLDelight — booleans sum as REAL; mapper coerces to
                // Int. Round-tripping a fractional sum here would be a
                // bug since is_completed is 0/1 only, but the mapper
                // accepts the field type the codegen produces.
                completed_items = 13.0,
                last_activity_at = later,
            )

        assertEquals(
            ListSummary(
                id = ListId("00000000-0000-4000-8000-000000000002"),
                name = "Work Q4",
                icon = FluxItIconRef.BRIEFCASE,
                color = ColorToken.ACCENT_INDIGO,
                isStarred = false,
                totalItems = 20,
                completedItems = 13,
                lastActivityAt = later,
            ),
            row.toSummary(),
        )
    }
}
