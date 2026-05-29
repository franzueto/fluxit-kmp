package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SetListStarredTest {
    @Test
    fun happy_path_sets_and_clears_starred() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft("Groceries")) as Outcome.Ok).value
            assertEquals(Outcome.Ok(Unit), SetListStarred(repo)(id, true))
            assertTrue(repo.observe(id).first()!!.isStarred)
            assertEquals(Outcome.Ok(Unit), SetListStarred(repo)(id, false))
            assertTrue(!repo.observe(id).first()!!.isStarred)
        }

    @Test
    fun missing_id_lifts_data_not_found_to_domain_not_found() =
        runTest {
            val repo = newRepo()
            val bogus = ListId("00000000-0000-4000-8000-bbbbbbbbbbbb")
            val result = SetListStarred(repo)(bogus, true)
            val err = assertIs<Outcome.Err<DomainError>>(result)
            assertEquals(DomainError.NotFound(entity = "List", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft("Groceries")) as Outcome.Ok).value
            val message = SetListStarred(repo)(id, true).fold(onOk = { "starred" }, onErr = { "failed: $it" })
            assertEquals("starred", message)
        }
}
