package dev.franzueto.fluxit.shared.domain.usecase.lists

import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeListsRepository
import kotlinx.datetime.Instant

/**
 * Shared fixtures for the Lists CRUD use-case tests (Phase 04 §7 / Slice 11A).
 * Mirrors the sequential-id + fixed-clock setup the §11 fake tests use, so
 * minted ids are deterministic and assertions can name them exactly.
 */
internal fun seqIds(): IdGenerator {
    var n = 0
    return IdGenerator {
        n++
        "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    }
}

internal fun newRepo(): FakeListsRepository =
    FakeListsRepository(
        ids = seqIds(),
        clock = FakeClock(Instant.fromEpochSeconds(1_700_000_000)),
    )

internal fun draft(name: String = "Groceries"): ListDraft =
    ListDraft(
        name = name,
        icon = FluxItIconRef.CART,
        color = ColorToken.PRIMARY_BLUE,
    )
