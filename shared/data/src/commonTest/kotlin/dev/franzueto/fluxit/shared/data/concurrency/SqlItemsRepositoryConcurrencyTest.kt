package dev.franzueto.fluxit.shared.data.concurrency

import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.fluxItDatabase
import dev.franzueto.fluxit.shared.data.db.inMemoryDriver
import dev.franzueto.fluxit.shared.data.repository.SqlItemsRepository
import dev.franzueto.fluxit.shared.data.repository.SqlListsRepository
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 03 §10 row (f) — concurrency invariant on the data layer.
 *
 * 50 concurrent `setCompleted` calls on the same item must:
 *   - all return `Outcome.Ok` (no Storage error from driver-level deadlock
 *     or connection contention),
 *   - complete inside a generous timeout (deadlock would hang forever),
 *   - leave the row readable and in a valid Boolean state.
 *
 * The final `isCompleted` value is non-deterministic by design — with 25
 * concurrent `true` writes and 25 concurrent `false` writes, the
 * last-write-wins value depends on scheduler ordering. "Consistent" here
 * means the row didn't corrupt or vanish, not that the value is pinned.
 */
class SqlItemsRepositoryConcurrencyTest {
    private val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val clock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    @Test
    fun fifty_concurrent_setCompleted_calls_do_not_deadlock_and_leave_a_readable_row() =
        runTest {
            var n = 0L
            val ids =
                IdGenerator {
                    n += 1
                    "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
                }
            val db = fluxItDatabase(inMemoryDriver())
            // Real dispatcher for the repos (NOT Unconfined) so Flow
            // operators don't accidentally serialize the test's
            // concurrent writes through the calling test scheduler.
            val lists =
                SqlListsRepository(db, clock = clock, ids = ids, dispatcher = Dispatchers.Default)
            val items =
                SqlItemsRepository(db, clock = clock, ids = ids, dispatcher = Dispatchers.Default)

            val listId =
                (
                    lists.create(
                        ListDraft(
                            name = "L",
                            icon = FluxItIconRef.CART,
                            color = ColorToken.PRIMARY_BLUE,
                        ),
                    ) as Outcome.Ok
                ).value
            val itemId: ItemId = (items.add(listId, ItemDraft("Milk")) as Outcome.Ok).value

            // No explicit withTimeout: `runTest` uses virtual time, which
            // would fire immediately against any wall-clock timeout (per
            // the error its own message suggests). The test runner's
            // outer timeout catches a real hang; an honest deadlock here
            // would surface as the harness killing the test, not as a
            // silent pass.
            val outcomes =
                withContext(Dispatchers.Default) {
                    (1..CONCURRENT_WRITES)
                        .map { i ->
                            async { items.setCompleted(itemId, completed = i % 2 == 0) }
                        }.awaitAll()
                }

            // Every write completed cleanly — no Storage errors from
            // driver-level contention.
            assertEquals(CONCURRENT_WRITES, outcomes.size)
            assertTrue(
                outcomes.all { it is Outcome.Ok },
                "expected all writes Ok; got: ${outcomes.filterIsInstance<Outcome.Err<*>>().map { it.error }}",
            )

            // Row still readable and the boolean field decoded cleanly
            // (no half-written state, no missing row).
            val finalItem = assertNotNull(items.observe(itemId).first())
            assertEquals(itemId, finalItem.id)
            // isCompleted is a Boolean; the read above already pinned
            // the type. The actual value is non-deterministic by design
            // (last-write-wins under contention) — assertion would be a
            // race.
        }

    private companion object {
        const val CONCURRENT_WRITES = 50
    }
}
