package dev.franzueto.fluxit.shared.domain.usecase.lists

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchListsTest {
    @Test
    fun matches_case_insensitive_substring() =
        runTest {
            val repo = newRepo()
            repo.create(draft("Groceries"))
            repo.create(draft("Hardware Store"))
            repo.create(draft("Wishlist"))
            val results = SearchLists(repo)("STORE").first()
            assertEquals(listOf("Hardware Store"), results.map { it.name })
        }

    @Test
    fun blank_query_returns_all_results_not_an_error() =
        runTest {
            val repo = newRepo()
            repo.create(draft("Groceries"))
            repo.create(draft("Wishlist"))
            // Validator-discipline: a blank query trims to "" → match everything.
            val results = SearchLists(repo)("   ").first()
            assertEquals(2, results.size)
        }
}
