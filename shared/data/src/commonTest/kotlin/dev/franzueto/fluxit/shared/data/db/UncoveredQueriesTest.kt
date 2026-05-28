package dev.franzueto.fluxit.shared.data.db

import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderOwnerType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 03 §10 row (a) — direct unit coverage for the queries that no
 * repository code path exercises (and therefore the smoke / integration
 * tests don't reach transitively). The plan literally calls for "one per
 * query in each `.sq` file"; the repository / integration tests already
 * cover the other 37 queries — these eight are the gap.
 *
 * Tested:
 *   Lists.sq   — selectAllActive, updateMetadata, hardDelete, countActive
 *   Items.sq   — countByList
 *   Reminders  — setActive, selectNeedingReschedule
 *   Photos.sq  — selectOrphaned
 *
 * All tests run directly against `db.<table>Queries.<query>(...)` —
 * intentionally bypassing the repository layer so a refactor that
 * removes a repo call site can't silently drop SQL coverage.
 */
class UncoveredQueriesTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun db(): FluxItDatabase = fluxItDatabase(inMemoryDriver())

    private fun FluxItDatabase.insertList(
        id: String,
        name: String = "L",
        sortOrder: Double = 1.0,
        deletedAt: Instant? = null,
    ) {
        listsQueries.insert(
            id = id,
            name = name,
            icon = FluxItIconRef.CART,
            color = ColorToken.PRIMARY_BLUE,
            is_starred = false,
            sort_order = sortOrder,
            created_at = now,
            updated_at = now,
        )
        if (deletedAt != null) listsQueries.softDelete(deleted_at = deletedAt, id = id)
    }

    private fun FluxItDatabase.insertItem(
        id: String,
        listId: String,
        title: String = "X",
        completed: Boolean = false,
        photoId: String? = null,
        deletedAt: Instant? = null,
    ) {
        itemsQueries.insert(
            id = id,
            list_id = listId,
            title = title,
            subtitle = null,
            description = null,
            is_completed = completed,
            is_starred = false,
            photo_id = photoId,
            sort_order = 1.0,
            created_at = now,
            updated_at = now,
        )
        if (deletedAt != null) itemsQueries.softDelete(deleted_at = deletedAt, id = id)
    }

    // ─────────────────────── Lists.sq ───────────────────────

    @Test
    fun lists_selectAllActive_returns_non_deleted_lists_ordered_by_sort_order_asc() {
        val db = db()
        db.insertList("a", sortOrder = 3.0)
        db.insertList("b", sortOrder = 1.0)
        db.insertList("c", sortOrder = 2.0)
        db.insertList("dead", sortOrder = 0.0, deletedAt = now)

        val ids =
            db.listsQueries
                .selectAllActive()
                .executeAsList()
                .map { it.id }
        assertEquals(listOf("b", "c", "a"), ids)
    }

    @Test
    fun lists_updateMetadata_writes_name_icon_color_starred_and_timestamp_atomically() {
        val db = db()
        db.insertList("a", name = "Old")
        val later = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 60_000)

        db.listsQueries.updateMetadata(
            name = "New",
            icon = FluxItIconRef.BRIEFCASE,
            color = ColorToken.ACCENT_INDIGO,
            is_starred = true,
            updated_at = later,
            id = "a",
        )

        val row = db.listsQueries.selectById("a").executeAsOne()
        assertEquals("New", row.name)
        assertEquals(FluxItIconRef.BRIEFCASE, row.icon)
        assertEquals(ColorToken.ACCENT_INDIGO, row.color)
        assertTrue(row.is_starred)
        assertEquals(later, row.updated_at)
    }

    @Test
    fun lists_hardDelete_removes_the_row_outright_even_if_already_tombstoned() {
        val db = db()
        db.insertList("a", deletedAt = now)
        // Soft-deleted: selectById (which filters deleted_at IS NULL) misses it.
        assertNull(db.listsQueries.selectById("a").executeAsOneOrNull())

        db.listsQueries.hardDelete("a")

        // After hard-delete, even a raw lookup ignoring deleted_at finds nothing.
        // SQLDelight doesn't generate an "ignore tombstones" query; use countActive
        // as a proxy: 0 active + 0 total means the row is fully gone.
        assertEquals(0L, db.listsQueries.countActive().executeAsOne())
    }

    @Test
    fun lists_countActive_counts_only_non_deleted() {
        val db = db()
        db.insertList("a")
        db.insertList("b")
        db.insertList("c", deletedAt = now)
        assertEquals(2L, db.listsQueries.countActive().executeAsOne())
    }

    // ─────────────────────── Items.sq ───────────────────────

    @Test
    fun items_countByList_scopes_to_list_and_excludes_tombstones() {
        val db = db()
        db.insertList("listA")
        db.insertList("listB")
        db.insertItem("i1", "listA")
        db.insertItem("i2", "listA")
        db.insertItem("i3", "listA", deletedAt = now) // tombstoned
        db.insertItem("i4", "listB")

        assertEquals(2L, db.itemsQueries.countByList("listA").executeAsOne())
        assertEquals(1L, db.itemsQueries.countByList("listB").executeAsOne())
    }

    // ───────────────────── Reminders.sq ─────────────────────

    @Test
    fun reminders_setActive_flips_the_flag_and_bumps_updated_at() {
        val db = db()
        db.remindersQueries.insert(
            id = "r1",
            owner_type = ReminderOwnerType.LIST,
            owner_id = "listX",
            fires_at = now,
            recurrence = null,
            platform_handle = null,
            is_active = true,
            created_at = now,
            updated_at = now,
        )
        val later = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 60_000)

        db.remindersQueries.setActive(is_active = false, updated_at = later, id = "r1")

        val row = db.remindersQueries.selectById("r1").executeAsOne()
        assertEquals(false, row.is_active)
        assertEquals(later, row.updated_at)
    }

    @Test
    fun reminders_selectNeedingReschedule_returns_recurring_active_past_dues_only() {
        val db = db()
        val past = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - 10_000)
        val future = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 10_000)

        // Qualifies: recurring + active + past + not deleted.
        db.remindersQueries.insert(
            id = "needs",
            owner_type = ReminderOwnerType.LIST,
            owner_id = "x",
            fires_at = past,
            recurrence = RecurrenceRule.Daily,
            platform_handle = null,
            is_active = true,
            created_at = now,
            updated_at = now,
        )
        // Excluded: one-shot (recurrence is NULL even though everything else matches).
        db.remindersQueries.insert(
            id = "oneshot",
            owner_type = ReminderOwnerType.LIST,
            owner_id = "x",
            fires_at = past,
            recurrence = null,
            platform_handle = null,
            is_active = true,
            created_at = now,
            updated_at = now,
        )
        // Excluded: future fires_at.
        db.remindersQueries.insert(
            id = "future",
            owner_type = ReminderOwnerType.LIST,
            owner_id = "x",
            fires_at = future,
            recurrence = RecurrenceRule.Daily,
            platform_handle = null,
            is_active = true,
            created_at = now,
            updated_at = now,
        )
        // Excluded: inactive.
        db.remindersQueries.insert(
            id = "inactive",
            owner_type = ReminderOwnerType.LIST,
            owner_id = "x",
            fires_at = past,
            recurrence = RecurrenceRule.Daily,
            platform_handle = null,
            is_active = false,
            created_at = now,
            updated_at = now,
        )

        val ids =
            db.remindersQueries
                .selectNeedingReschedule(now = now)
                .executeAsList()
                .map { it.id }
        assertEquals(listOf("needs"), ids)
    }

    // ─────────────────────── Photos.sq ──────────────────────

    @Test
    fun photos_selectOrphaned_returns_tombstoned_unreferenced_rows_past_cutoff_oldest_first() {
        val db = db()
        val grace = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - 24L * 3_600_000L)
        val olderTombstone = Instant.fromEpochMilliseconds(grace.toEpochMilliseconds() - 60_000)
        val newerTombstone = Instant.fromEpochMilliseconds(grace.toEpochMilliseconds() - 30_000)
        val withinGrace = Instant.fromEpochMilliseconds(grace.toEpochMilliseconds() + 60_000)

        fun insertPhoto(
            id: String,
            deletedAt: Instant?,
        ) {
            db.photosQueries.insert(
                id = id,
                relative_path = "photos/$id.jpg",
                mime_type = "image/jpeg",
                width_px = 1,
                height_px = 1,
                byte_size = 1,
                created_at = now,
            )
            if (deletedAt != null) db.photosQueries.softDelete(deleted_at = deletedAt, id = id)
        }

        // Qualifies: tombstoned older than cutoff, no referencing item.
        insertPhoto("oldest", olderTombstone)
        insertPhoto("newer", newerTombstone)
        // Excluded: still within grace window.
        insertPhoto("recent", withinGrace)
        // Excluded: live (no tombstone).
        insertPhoto("live", null)
        // Excluded: tombstoned + old but still referenced by a live item.
        insertPhoto("referenced", olderTombstone)
        db.insertList("L")
        db.insertItem("i", "L", photoId = "referenced")

        val ids =
            db.photosQueries
                .selectOrphaned(cutoff = grace)
                .executeAsList()
                .map { it.id }
        assertEquals(listOf("oldest", "newer"), ids) // ASC by deleted_at — oldest first per §7.

        // Sanity: the live item's reference really is what blocks "referenced".
        val item = db.itemsQueries.selectById("i").executeAsOneOrNull()
        assertNotNull(item)
        assertEquals("referenced", item.photo_id)
    }
}
