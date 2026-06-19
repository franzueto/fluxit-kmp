package dev.franzueto.fluxit.feature.listdetail

import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the pure completion-header formatter in [ListDetailComponents]
 * (`completionFraction`). Covers the empty (divide-by-zero guard), partial, and
 * fully-complete cases that drive the §1 progress bar (plan/08 §11).
 */
class CompletionFractionTest {
    private val epoch = Instant.fromEpochSeconds(0)

    private fun item(
        id: String,
        completed: Boolean,
    ): Item =
        Item(
            id = ItemId(id),
            listId = ListId("l1"),
            title = id,
            subtitle = null,
            description = null,
            isCompleted = completed,
            isStarred = false,
            photoId = null,
            createdAt = epoch,
            updatedAt = epoch,
        )

    private fun section(
        active: Int,
        completed: Int,
    ): ItemsSection =
        ItemsSection(
            active = (0 until active).map { item("a$it", completed = false) },
            completed = (0 until completed).map { item("c$it", completed = true) },
            total = active + completed,
            completedCount = completed,
        )

    @Test
    fun empty_section_is_zero() {
        assertEquals(0f, completionFraction(section(active = 0, completed = 0)))
    }

    @Test
    fun half_complete_is_half() {
        assertEquals(0.5f, completionFraction(section(active = 2, completed = 2)))
    }

    @Test
    fun fully_complete_is_one() {
        assertEquals(1f, completionFraction(section(active = 0, completed = 3)))
    }

    @Test
    fun none_complete_is_zero() {
        assertEquals(0f, completionFraction(section(active = 4, completed = 0)))
    }
}
