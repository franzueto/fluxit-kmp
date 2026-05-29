package dev.franzueto.fluxit.shared.domain.usecase.items

import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakeListsRepository
import kotlinx.datetime.Instant

/**
 * Shared fixtures for the Items CRUD use-case tests (Phase 04 §7 / Slice 12).
 * Each fake gets its own sequential-id generator (distinct prefixes so a
 * `ListId` and an `ItemId` can never collide in an assertion) + a fixed
 * clock, mirroring the §11 fake-test setup.
 */
internal fun seqIds(prefix: String): IdGenerator {
    var n = 0
    return IdGenerator {
        n++
        "$prefix-${n.toString().padStart(8, '0')}"
    }
}

internal fun fixedClock(): FakeClock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))

internal fun newItemsRepo(): FakeItemsRepository = FakeItemsRepository(ids = seqIds("item"), clock = fixedClock())

internal fun newListsRepo(): FakeListsRepository = FakeListsRepository(ids = seqIds("list"), clock = fixedClock())

internal fun listDraft(name: String = "Groceries"): ListDraft =
    ListDraft(name = name, icon = FluxItIconRef.CART, color = ColorToken.PRIMARY_BLUE)

internal fun itemDraft(title: String = "Milk"): ItemDraft = ItemDraft(title = title)
