package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.error.fold
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.rule.PaletteCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateListAppearanceTest {
    @Test
    fun happy_path_updates_icon_and_color() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft("Groceries")) as Outcome.Ok).value
            val result = UpdateListAppearance(repo)(id, FluxItIconRef.HOME, ColorToken.ACCENT_EMERALD)
            assertEquals(Outcome.Ok(Unit), result)
            val detail = repo.observe(id).first()!!
            assertEquals(FluxItIconRef.HOME, detail.icon)
            assertEquals(ColorToken.ACCENT_EMERALD, detail.color)
        }

    @Test
    fun every_catalog_value_passes_the_guard() =
        runTest {
            // The PaletteCatalog guard is a forward-looking seam: with the v1
            // full-enum catalog it must never reject a well-typed argument.
            // Exercising every (icon, color) the catalog surfaces proves the
            // membership check passes through for all v1-reachable inputs.
            val repo = newRepo()
            val id = (repo.create(draft("Groceries")) as Outcome.Ok).value
            for (icon in PaletteCatalog.icons) {
                for (color in PaletteCatalog.colors) {
                    assertEquals(Outcome.Ok(Unit), UpdateListAppearance(repo)(id, icon, color))
                }
            }
        }

    @Test
    fun missing_id_lifts_data_not_found_to_domain_not_found() =
        runTest {
            val repo = newRepo()
            val bogus = ListId("00000000-0000-4000-8000-bbbbbbbbbbbb")
            val result = UpdateListAppearance(repo)(bogus, FluxItIconRef.HOME, ColorToken.ACCENT_EMERALD)
            val err = assertIs<Outcome.Err<DomainError>>(result)
            assertEquals(DomainError.NotFound(entity = "List", id = bogus.value), err.error)
        }

    @Test
    fun fold_use_site_collapses_both_channels() =
        runTest {
            val repo = newRepo()
            val id = (repo.create(draft("Groceries")) as Outcome.Ok).value
            val message =
                UpdateListAppearance(repo)(id, FluxItIconRef.HOME, ColorToken.ACCENT_EMERALD)
                    .fold(onOk = { "updated" }, onErr = { "failed: $it" })
            assertEquals("updated", message)
        }
}
