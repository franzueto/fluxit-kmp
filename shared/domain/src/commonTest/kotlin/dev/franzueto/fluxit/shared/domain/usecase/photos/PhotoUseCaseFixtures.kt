package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakePhotoStorage
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakePhotosRepository
import kotlinx.datetime.Instant

/**
 * Shared fixtures for the Photos use-case tests (Phase 04 §7 / Slice 13D).
 * Distinct id prefixes per repo so ids never collide in an assertion.
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

internal fun newPhotosRepo(
    storage: FakePhotoStorage,
    isReferenced: (PhotoId) -> Boolean = { false },
): FakePhotosRepository =
    FakePhotosRepository(
        storage = storage,
        ids = seqIds("photo"),
        clock = fixedClock(),
        isReferenced = isReferenced,
    )

internal val sampleListId: ListId = ListId("list-00000001")

internal fun itemDraft(title: String = "Milk"): ItemDraft = ItemDraft(title = title)
