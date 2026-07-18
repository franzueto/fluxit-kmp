package dev.franzueto.fluxit.shared.state.debug

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.usecase.items.AddItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ToggleItemCompleted
import dev.franzueto.fluxit.shared.domain.usecase.lists.CreateList

/**
 * Seeding is **not transactional** — each list/item is its own write. On the first
 * use-case failure it returns that [DomainError] and stops; any rows written
 * before the failure stay (acceptable for a debug convenience). On success it
 * returns the number of lists created.
 */
public class SeedSampleData(
    private val createList: CreateList,
    private val addItem: AddItem,
    private val toggleItemCompleted: ToggleItemCompleted,
) {
    public suspend operator fun invoke(): Outcome<Int, DomainError> {
        for (sample in SAMPLE_LISTS) {
            val listId =
                when (val created = createList(ListDraft(name = sample.name, icon = sample.icon, color = sample.color))) {
                    is Outcome.Err -> return created
                    is Outcome.Ok -> created.value
                }
            sample.items.forEachIndexed { index, title ->
                val itemId =
                    when (val added = addItem(listId, ItemDraft(title = title))) {
                        is Outcome.Err -> return added
                        is Outcome.Ok -> added.value
                    }
                // Mark the first `completedCount` items done so the dashboard
                // shows a spread of "X% completed" / "No items yet" subtitles.
                if (index < sample.completedCount) {
                    when (val toggled = toggleItemCompleted(itemId)) {
                        is Outcome.Err -> return toggled
                        is Outcome.Ok -> Unit
                    }
                }
            }
        }
        return Outcome.Ok(SAMPLE_LISTS.size)
    }

    private data class SampleList(
        val name: String,
        val icon: FluxItIconRef,
        val color: ColorToken,
        val items: List<String>,
        val completedCount: Int,
    )

    private companion object {
        val SAMPLE_LISTS =
            listOf(
                SampleList(
                    name = "Supermarket",
                    icon = FluxItIconRef.CART,
                    color = ColorToken.ACCENT_EMERALD,
                    items = listOf("Milk", "Eggs", "Sourdough bread", "Bananas", "Coffee beans", "Olive oil", "Spinach"),
                    completedCount = 3,
                ),
                SampleList(
                    name = "Home To-Do",
                    icon = FluxItIconRef.HOME,
                    color = ColorToken.ACCENT_ORANGE,
                    items = listOf("Fix kitchen tap", "Hang shelves", "Water the plants", "Replace air filter", "Call plumber"),
                    completedCount = 2,
                ),
                SampleList(
                    name = "Trip to Japan",
                    icon = FluxItIconRef.PLANE,
                    color = ColorToken.ACCENT_SKY,
                    items =
                        listOf(
                            "Renew passport",
                            "Book flights",
                            "Reserve ryokan",
                            "JR Pass",
                            "Pocket wifi",
                            "Pack adapters",
                            "Travel insurance",
                        ),
                    completedCount = 2,
                ),
                SampleList(
                    name = "Gift Ideas",
                    icon = FluxItIconRef.STAR,
                    color = ColorToken.ACCENT_ROSE,
                    items = listOf("Headphones for Mom", "Book for Alex", "Plant for the office"),
                    completedCount = 0,
                ),
                SampleList(
                    name = "Work Q4 Goals",
                    icon = FluxItIconRef.BRIEFCASE,
                    color = ColorToken.ACCENT_INDIGO,
                    items =
                        listOf(
                            "Ship mobile release",
                            "Write design doc",
                            "Mentor new hire",
                            "Close out tech debt",
                            "Prepare Q1 goals",
                            "Performance reviews",
                        ),
                    completedCount = 1,
                ),
            )
    }
}
