package dev.franzueto.fluxit.shared.domain.repository

import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class FakeRemindersRepositoryTest {
    private val epoch = Instant.fromEpochSeconds(1_700_000_000)
    private val listOwner = ReminderOwner.OfList(ListId("00000000-0000-4000-8000-aaaaaaaaaaaa"))
    private val itemOwner = ReminderOwner.OfItem(ItemId("00000000-0000-4000-8000-bbbbbbbbbbbb"))

    private fun seqIds(): IdGenerator {
        var n = 0
        return IdGenerator {
            n++
            "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        }
    }

    private fun newRepo(clock: FakeClock = FakeClock(epoch)): Pair<FakeRemindersRepository, FakeClock> {
        val repo = FakeRemindersRepository(ids = seqIds(), clock = clock)
        return repo to clock
    }

    private fun spec(
        owner: ReminderOwner = listOwner,
        firesAt: Instant = epoch.plus(1.hours),
        recurrence: RecurrenceRule = RecurrenceRule.None,
    ) = ReminderSpec(owner = owner, firesAt = firesAt, recurrence = recurrence)

    @Test
    fun schedule_then_observe_for_owner_returns_active_reminder() =
        runTest {
            val (repo, _) = newRepo()
            val id = (repo.schedule(spec()) as Outcome.Ok).value
            val active = repo.observeForOwner(listOwner).first()
            assertEquals(1, active.size)
            assertEquals(id, active.single().id)
            assertTrue(active.single().isActive)
        }

    @Test
    fun observe_for_owner_filters_by_owner() =
        runTest {
            val (repo, _) = newRepo()
            val mine = (repo.schedule(spec(owner = listOwner)) as Outcome.Ok).value
            repo.schedule(spec(owner = itemOwner))
            val active = repo.observeForOwner(listOwner).first()
            assertEquals(1, active.size)
            assertEquals(mine, active.single().id)
        }

    @Test
    fun cancel_tombstones_row_and_clears_from_observers() =
        runTest {
            val (repo, _) = newRepo()
            val id = (repo.schedule(spec()) as Outcome.Ok).value
            assertEquals(Outcome.Ok(Unit), repo.cancel(id))
            assertEquals(emptyList(), repo.observeForOwner(listOwner).first())
        }

    @Test
    fun reschedule_updates_fires_at_and_recurrence() =
        runTest {
            val (repo, _) = newRepo()
            val id = (repo.schedule(spec()) as Outcome.Ok).value
            val later = epoch.plus(2.hours)
            assertEquals(Outcome.Ok(Unit), repo.reschedule(id, later, RecurrenceRule.Daily))
            val r = repo.observeForOwner(listOwner).first().single()
            assertEquals(later, r.firesAt)
            assertEquals(RecurrenceRule.Daily, r.recurrence)
        }

    @Test
    fun rebind_platform_handle_round_trips() =
        runTest {
            val (repo, _) = newRepo()
            val id = (repo.schedule(spec()) as Outcome.Ok).value
            assertNull(
                repo
                    .observeForOwner(listOwner)
                    .first()
                    .single()
                    .platformHandle,
            )
            assertEquals(Outcome.Ok(Unit), repo.rebindPlatformHandle(id, "wm-job-42"))
            assertEquals(
                "wm-job-42",
                repo
                    .observeForOwner(listOwner)
                    .first()
                    .single()
                    .platformHandle,
            )
            assertEquals(Outcome.Ok(Unit), repo.rebindPlatformHandle(id, null))
            assertNull(
                repo
                    .observeForOwner(listOwner)
                    .first()
                    .single()
                    .platformHandle,
            )
        }

    @Test
    fun writes_on_missing_id_return_not_found() =
        runTest {
            val (repo, _) = newRepo()
            val bogus = ReminderId("00000000-0000-4000-8000-cccccccccccc")
            val err = assertIs<Outcome.Err<DataError>>(repo.cancel(bogus))
            assertEquals(DataError.NotFound(bogus.value), err.error)
        }

    @Test
    fun observe_upcoming_snapshots_now_at_subscription() =
        runTest {
            val clock = FakeClock(epoch)
            val (repo, _) = newRepo(clock)
            repo.schedule(spec(firesAt = epoch.plus(30.minutes))) // upcoming
            repo.schedule(spec(firesAt = epoch.minus(30.minutes))) // already past
            val upcoming = repo.observeUpcoming(limit = 10).first()
            assertEquals(1, upcoming.size)
            assertEquals(epoch.plus(30.minutes), upcoming.single().firesAt)
        }

    @Test
    fun observe_upcoming_respects_limit_and_sorts_ascending() =
        runTest {
            val (repo, _) = newRepo()
            repo.schedule(spec(firesAt = epoch.plus(3.hours)))
            repo.schedule(spec(firesAt = epoch.plus(1.hours)))
            repo.schedule(spec(firesAt = epoch.plus(2.hours)))
            val upcoming = repo.observeUpcoming(limit = 2).first()
            assertEquals(
                listOf(epoch.plus(1.hours), epoch.plus(2.hours)),
                upcoming.map { it.firesAt },
            )
        }

    @Test
    fun observe_upcoming_rejects_non_positive_limit() =
        runTest {
            val (repo, _) = newRepo()
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                repo.observeUpcoming(limit = 0)
            }
        }
}
