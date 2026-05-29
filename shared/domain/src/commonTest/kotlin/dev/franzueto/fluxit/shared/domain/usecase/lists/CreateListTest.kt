package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.ValidationError
import dev.franzueto.fluxit.shared.domain.error.fold
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CreateListTest {
    @Test
    fun happy_path_persists_list_and_returns_id() =
        runTest {
            val repo = newRepo()
            val result = CreateList(repo)(draft("Garden"))
            val id = assertIs<Outcome.Ok<*>>(result).value
            assertEquals(
                id,
                repo
                    .observeAll()
                    .first()
                    .single()
                    .id,
            )
        }

    @Test
    fun trims_name_before_persisting() =
        runTest {
            val repo = newRepo()
            CreateList(repo)(draft("  Garden  "))
            assertEquals(
                "Garden",
                repo
                    .observeAll()
                    .first()
                    .single()
                    .name,
            )
        }

    @Test
    fun blank_name_yields_domain_validation_empty() =
        runTest {
            val repo = newRepo()
            val result = CreateList(repo)(draft("   "))
            val err = assertIs<Outcome.Err<DomainError>>(result)
            assertEquals(DomainError.Validation(field = "name", rule = ValidationError.Empty), err.error)
            // Validation rejects at the edge — nothing reaches storage.
            assertTrue(repo.observeAll().first().isEmpty())
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val repo = newRepo()
            val message =
                CreateList(repo)(draft("Garden")).fold(
                    onOk = { "created ${it.value}" },
                    onErr = { "failed: $it" },
                )
            assertTrue(message.startsWith("created"))
        }
}
