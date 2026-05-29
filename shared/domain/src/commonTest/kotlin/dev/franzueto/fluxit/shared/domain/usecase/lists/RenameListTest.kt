package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ListId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RenameListTest {
    @Test
    fun happy_path_renames_and_trims() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft("Groceries")) as Outcome.Ok).value
            val result = RenameList(repo)(id, "  Pantry  ")
            assertEquals(Outcome.Ok(Unit), result)
            assertEquals("Pantry", repo.observe(id).first()!!.name)
        }

    @Test
    fun blank_name_yields_domain_validation_empty() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft("Groceries")) as Outcome.Ok).value
            val result = RenameList(repo)(id, "   ")
            val err = assertIs<Outcome.Err<DomainError>>(result)
            assertEquals(DomainError.Validation(field = "name", rule = ValidationError.Empty), err.error)
            // Original name untouched.
            assertEquals("Groceries", repo.observe(id).first()!!.name)
        }

    @Test
    fun missing_id_lifts_data_not_found_to_domain_not_found() =
        runTest {
            val repo = newRepo()
            val bogus = ListId("00000000-0000-4000-8000-bbbbbbbbbbbb")
            val result = RenameList(repo)(bogus, "Pantry")
            val err = assertIs<Outcome.Err<DomainError>>(result)
            // The mapError { it.toDomain(entity = "List") } lift in action.
            assertEquals(DomainError.NotFound(entity = "List", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft("Groceries")) as Outcome.Ok).value
            val message =
                RenameList(repo)(id, "Pantry").fold(
                    onOk = { "renamed" },
                    onErr = { "failed: $it" },
                )
            assertTrue(message == "renamed")
        }
}
