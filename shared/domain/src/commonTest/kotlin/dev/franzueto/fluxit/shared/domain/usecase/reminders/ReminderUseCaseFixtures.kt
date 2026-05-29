package dev.franzueto.fluxit.shared.domain.usecase.reminders

import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeRemindersRepository
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

/**
 * Shared fixtures for the Reminders use-case tests (Phase 04 §7 / Slice 13C).
 * Mirrors the Lists/Items fixtures: a sequential-id generator + a fixed clock
 * so minted ids and the "now" reference are deterministic.
 */
internal val FIXED_NOW: Instant = Instant.fromEpochSeconds(1_700_000_000)

internal fun seqReminderIds(): IdGenerator {
    var n = 0
    return IdGenerator {
        n++
        "reminder-${n.toString().padStart(8, '0')}"
    }
}

internal fun newRemindersRepo(clock: FakeClock = FakeClock(FIXED_NOW)): FakeRemindersRepository =
    FakeRemindersRepository(ids = seqReminderIds(), clock = clock)

internal val sampleListId: ListId = ListId("list-00000001")

internal fun listReminderSpec(
    firesAt: Instant = FIXED_NOW.plus(1.hours),
    recurrence: RecurrenceRule = RecurrenceRule.None,
): ReminderSpec =
    ReminderSpec(
        owner = ReminderOwner.OfList(sampleListId),
        firesAt = firesAt,
        recurrence = recurrence,
    )
