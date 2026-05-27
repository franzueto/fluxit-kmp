package dev.franzueto.fluxit.shared.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.data.mapper.toDomain
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.Reminder
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.repository.RemindersRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * SQLDelight-backed [RemindersRepository] (Phase 03 §5, 3/4). Pure row-
 * writer; no platform scheduling — that ships in Phase 06 and observes
 * this repo's flows. `RecurrenceRule.None` collapses to NULL at the
 * storage edge per the §3 contract.
 */
public class SqlRemindersRepository(
    private val database: FluxItDatabase,
    private val clock: Clock = Clock.System,
    private val ids: IdGenerator = IdGenerator.System,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RemindersRepository {
    private val queries get() = database.remindersQueries

    override fun observeForOwner(owner: ReminderOwner): Flow<List<Reminder>> =
        queries
            .selectActiveByOwner(owner.type, owner.id)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeUpcoming(limit: Int): Flow<List<Reminder>> {
        // "Now" snapshot at subscription time — DB-driven re-emission only.
        // v2 Calendar will need to combine with a wall-clock ticker for
        // rows that cross the threshold without a row update.
        val now = clock.now()
        return queries
            .selectAllUpcoming(now = now, limit = limit.toLong())
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun schedule(spec: ReminderSpec): Outcome<ReminderId, DataError> =
        guard {
            val now = clock.now()
            val id = ReminderId(ids.newId())
            queries.insert(
                id = id.value,
                owner_type = spec.owner.type,
                owner_id = spec.owner.id,
                fires_at = spec.firesAt,
                recurrence = spec.recurrence.toStorage(),
                platform_handle = null,
                is_active = true,
                created_at = now,
                updated_at = now,
            )
            Outcome.Ok(id)
        }

    override suspend fun reschedule(
        id: ReminderId,
        firesAt: kotlinx.datetime.Instant,
        recurrence: RecurrenceRule,
    ): Outcome<Unit, DataError> =
        guard {
            queries.updateFiresAt(
                fires_at = firesAt,
                recurrence = recurrence.toStorage(),
                updated_at = clock.now(),
                id = id.value,
            )
            requireExists(id)
        }

    override suspend fun cancel(id: ReminderId): Outcome<Unit, DataError> =
        guard {
            database.transactionWithResult {
                // Same pre-check pattern as Lists/Items.delete — soft-delete
                // WHERE hides the row post-update, so a post-update existence
                // check would falsely report NotFound on the first cancel.
                if (queries.selectById(id.value).executeAsOneOrNull() == null) {
                    Outcome.Err(DataError.NotFound(id.value))
                } else {
                    queries.softDelete(deleted_at = clock.now(), id = id.value)
                    Outcome.Ok(Unit)
                }
            }
        }

    override suspend fun rebindPlatformHandle(
        id: ReminderId,
        handle: String?,
    ): Outcome<Unit, DataError> =
        guard {
            queries.setPlatformHandle(
                platform_handle = handle,
                updated_at = clock.now(),
                id = id.value,
            )
            requireExists(id)
        }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun requireExists(id: ReminderId): Outcome<Unit, DataError> =
        if (queries.selectById(id.value).executeAsOneOrNull() == null) {
            Outcome.Err(DataError.NotFound(id.value))
        } else {
            Outcome.Ok(Unit)
        }

    /** §3 storage contract: None ≡ NULL on the wire; all other variants serialize. */
    private fun RecurrenceRule.toStorage(): RecurrenceRule? = if (this is RecurrenceRule.None) null else this

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> guard(block: () -> Outcome<T, DataError>): Outcome<T, DataError> =
        try {
            block()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Outcome.Err(DataError.Storage(e))
        }
}
