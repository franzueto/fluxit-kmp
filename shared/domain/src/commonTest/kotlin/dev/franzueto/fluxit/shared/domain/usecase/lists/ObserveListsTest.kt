package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveListsTest {
    @Test
    fun emits_empty_then_reflects_created_lists() =
        runTest {
            val repo = newRepo()
            val observe = ObserveLists(repo)
            assertEquals(emptyList(), observe().first())

            val id = (repo.create(draft("Garden")) as Outcome.Ok).value
            val summaries = observe().first()
            assertEquals(1, summaries.size)
            assertEquals(id, summaries.single().id)
            assertEquals("Garden", summaries.single().name)
        }
}
