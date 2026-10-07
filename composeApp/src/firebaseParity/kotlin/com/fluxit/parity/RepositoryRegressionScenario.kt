package com.fluxit.parity

import com.fluxit.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Firebase real-adapter contract regression. Checkpoints validate observable
 * order/content/counts without relying on IDs or timestamps. No SDK types cross this seam. */
object RepositoryRegressionScenario {
    // Fixed observable contract previously compared against Room in.
    // Keep the expected values independent of the current Firebase implementation.
    private val expectedTrace = listOf(
        "empty:Parity:CART:PRIMARY_BLUE:0:0:",
        "edit-list:Updated:HOME:EMERALD:0:0:",
        "add:Updated:HOME:EMERALD:1:0:alpha,null,false,false",
        "order:Updated:HOME:EMERALD:2:0:alpha,null,false,false|beta,null,false,false",
        "edit-item:Updated:HOME:EMERALD:2:0:alpha edited,description,false,false|beta,null,false,false",
        "complete-retry:Updated:HOME:EMERALD:2:1:alpha edited,description,true,false|beta,null,false,false",
        "uncomplete:Updated:HOME:EMERALD:2:0:alpha edited,description,false,false|beta,null,false,false",
        "photo-reference:Updated:HOME:EMERALD:2:0:alpha edited,description,false,true|beta,null,false,false",
        "remove-photo:Updated:HOME:EMERALD:2:0:alpha edited,description,false,false|beta,null,false,false",
        "delete-retry:Updated:HOME:EMERALD:1:0:beta,null,false,false",
        "undo-retry:Updated:HOME:EMERALD:2:0:alpha edited,description,false,false|beta,null,false,false",
        "clear-completed-retry:Updated:HOME:EMERALD:1:0:beta,null,false,false",
        "undo-completed:Updated:HOME:EMERALD:2:1:alpha edited,description,true,false|beta,null,false,false",
        "hard-delete-retry:Updated:HOME:EMERALD:1:1:alpha edited,description,true,false",
        "delete-list:hidden",
        "undo-list:Updated:HOME:EMERALD:1:1:alpha edited,description,true,false",
    )

    suspend fun run(lists: ListRepository, items: ItemRepository, photoRef: (String) -> String): List<String> {
        val trace = mutableListOf<String>()
        val id = lists.createList("Parity", ListIcon.CART, ListColor.PRIMARY_BLUE)
        suspend fun checkpoint(label: String, titles: List<String>, total: Int, completed: Int, photo: Boolean = false) {
            val current = withTimeout(15_000) { items.observeItems(id).first { it.map { i -> i.title } == titles && it.count { i -> i.isCompleted } == completed &&
                it.any { i -> i.photoRef != null } == photo &&
                it.all { i -> i.description == if (i.title == "alpha edited") "description" else null } } }
            val summary = withTimeout(15_000) { lists.observeListSummaries().first { rows ->
                rows.any { it.list.id == id && it.totalItems == total && it.completedItems == completed }
            } }.first { it.list.id == id }
            trace += "$label:${summary.list.name}:${summary.list.icon}:${summary.list.color}:$total:$completed:" +
                current.joinToString("|") { "${it.title},${it.description},${it.isCompleted},${it.photoRef != null}" }
        }
        checkpoint("empty", emptyList(), 0, 0)
        lists.updateList(id, "Updated", ListIcon.HOME, ListColor.EMERALD)
        check(withTimeout(15_000) { lists.observeList(id).first { it?.name == "Updated" } }?.icon == ListIcon.HOME)
        checkpoint("edit-list", emptyList(), 0, 0)
        items.addItem(id, "alpha")
        checkpoint("add", listOf("alpha"), 1, 0)
        delay(30) // distinct timestamps; same-timestamp tie-breaking is tested separately
        items.addItem(id, "beta")
        checkpoint("order", listOf("alpha", "beta"), 2, 0)
        val rows = withTimeout(15_000) { items.observeItems(id).first { it.size == 2 } }
        val a = rows[0].id
        val b = rows[1].id
        items.updateItem(id, a, "alpha edited", "description")
        checkpoint("edit-item", listOf("alpha edited", "beta"), 2, 0)
        items.setCompleted(id, a, true)
        items.setCompleted(id, a, true) // retry must not double-count
        checkpoint("complete-retry", listOf("alpha edited", "beta"), 2, 1)
        items.setCompleted(id, a, false)
        checkpoint("uncomplete", listOf("alpha edited", "beta"), 2, 0)
        items.setPhotoRef(id, a, photoRef(a))
        val withPhoto = withTimeout(15_000) { items.observeItem(id, a).first { it?.photoRef != null } }
        check(withPhoto?.description == "description")
        checkpoint("photo-reference", listOf("alpha edited", "beta"), 2, 0, photo = true)
        items.setPhotoRef(id, a, null)
        checkpoint("remove-photo", listOf("alpha edited", "beta"), 2, 0)
        items.softDeleteItem(id, a)
        items.softDeleteItem(id, a)
        check(withTimeout(15_000) { items.observeItem(id, a).first { it == null } } == null)
        checkpoint("delete-retry", listOf("beta"), 1, 0)
        items.restoreItem(id, a)
        items.restoreItem(id, a)
        checkpoint("undo-retry", listOf("alpha edited", "beta"), 2, 0)
        items.setCompleted(id, a, true)
        items.clearCompleted(id)
        items.clearCompleted(id)
        checkpoint("clear-completed-retry", listOf("beta"), 1, 0)
        items.restoreItem(id, a)
        checkpoint("undo-completed", listOf("alpha edited", "beta"), 2, 1)
        items.deleteItem(id, b)
        items.deleteItem(id, b)
        checkpoint("hard-delete-retry", listOf("alpha edited"), 1, 1)
        lists.softDeleteList(id)
        check(withTimeout(15_000) { lists.observeListSummaries().first { r -> r.none { it.list.id == id } } }.none { it.list.id == id })
        check(withTimeout(15_000) { lists.observeList(id).first { it == null } } == null)
        trace += "delete-list:hidden"
        lists.restoreList(id)
        checkpoint("undo-list", listOf("alpha edited"), 1, 1)
        check(trace == expectedTrace) { "Firebase observable contract differs from the reviewed baseline" }
        return trace
    }
}
