package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * In-memory [RemindersRepository] for §7 use-case tests (Phase 04 §11).
 * Same MutableStateFlow pattern as the other fakes — tombstone
 * filtering on reads, `NotFound` returns on writes to missing /
 * tombstoned ids.
 *
 * `observeUpcoming(limit)` snapshots `clock.now()` once at
 * subscription time per the §5 spec ("'Now' is captured once at
 * subscription — re-emission is DB-driven, not wall-clock-driven").
 * Subsequent emissions filter against that frozen snapshot, so a
 * reminder whose `firesAt` slips into the past while the collector
 * is active still flows through.
 *
 * `cancel` sets both `isActive = false` AND `deletedAt = now`. The
 * Phase 03 SqlRemindersRepository follows the same shape: cancel
 * tombstones the row + flips the active flag in one write.
 */
public class FakeRemindersRepository(
    private val ids: IdGenerator,
    private val clock: Clock,
) : RemindersRepository {
    private data class Row(
        val id: ReminderId,
        val owner: ReminderOwner,
        val firesAt: Instant,
        val recurrence: RecurrenceRule,
        val platformHandle: String?,
        val isActive: Boolean,
        val createdAt: Instant,
        val updatedAt: Instant,
        val deletedAt: Instant?,
    )

    private val state = MutableStateFlow<List<Row>>(emptyList())

    /**
     * Controllable failure mode (Phase 04 §11). When non-null, [schedule]
     * short-circuits with this [DataError] before persisting — lets
     * `ScheduleReminder`'s tests drive the repository-persist-failure branch
     * (the `mapError { it.toDomain("Reminder") }` lift) without a real DB.
     */
    public var failScheduleWith: DataError? = null

    // ── reads ────────────────────────────────────────────────────────────

    override fun observeForOwner(owner: ReminderOwner): Flow<List<Reminder>> =
        state.map { rows ->
            rows
                .filter { it.owner == owner && it.isActive && it.deletedAt == null }
                .sortedBy { it.firesAt }
                .map { it.toReminder() }
        }

    override fun observeUpcoming(limit: Int): Flow<List<Reminder>> {
        require(limit > 0) { "limit must be > 0: $limit" }
        // "Now" snapshot at subscription time (§5 spec). flow { } gives
        // each new collector its own setup block where we freeze `now`
        // before emitting the filtered state — re-subscribing yields a
        // fresh snapshot, which matches what the SQL impl will do via a
        // WHERE fires_at > :nowSnapshot binding.
        return flow {
            val now = clock.now()
            emitAll(
                state.map { rows ->
                    rows
                        .filter { it.isActive && it.deletedAt == null && it.firesAt > now }
                        .sortedBy { it.firesAt }
                        .take(limit)
                        .map { it.toReminder() }
                },
            )
        }
    }

    // ── writes ───────────────────────────────────────────────────────────

    override suspend fun schedule(spec: ReminderSpec): Outcome<ReminderId, DataError> {
        failScheduleWith?.let { return Outcome.Err(it) }
        val now = clock.now()
        val id = ReminderId(ids.newId())
        val row =
            Row(
                id = id,
                owner = spec.owner,
                firesAt = spec.firesAt,
                recurrence = spec.recurrence,
                platformHandle = null,
                isActive = true,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        state.value = state.value + row
        return Outcome.Ok(id)
    }

    override suspend fun reschedule(
        id: ReminderId,
        firesAt: Instant,
        recurrence: RecurrenceRule,
    ): Outcome<Unit, DataError> = mutate(id) { it.copy(firesAt = firesAt, recurrence = recurrence, updatedAt = clock.now()) }

    override suspend fun cancel(id: ReminderId): Outcome<Unit, DataError> {
        val now = clock.now()
        return mutate(id) {
            it.copy(isActive = false, deletedAt = now, updatedAt = now)
        }
    }

    override suspend fun rebindPlatformHandle(
        id: ReminderId,
        handle: String?,
    ): Outcome<Unit, DataError> = mutate(id) { it.copy(platformHandle = handle, updatedAt = clock.now()) }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun mutate(
        id: ReminderId,
        transform: (Row) -> Row,
    ): Outcome<Unit, DataError> {
        val current = state.value
        if (current.none { it.id == id && it.deletedAt == null }) {
            return Outcome.Err(DataError.NotFound(id.value))
        }
        state.value =
            current.map { r ->
                if (r.id == id && r.deletedAt == null) transform(r) else r
            }
        return Outcome.Ok(Unit)
    }

    private fun Row.toReminder(): Reminder =
        Reminder(
            id = id,
            owner = owner,
            firesAt = firesAt,
            recurrence = recurrence,
            platformHandle = platformHandle,
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
