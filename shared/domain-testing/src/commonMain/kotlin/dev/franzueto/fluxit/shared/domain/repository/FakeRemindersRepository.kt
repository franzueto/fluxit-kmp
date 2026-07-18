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
