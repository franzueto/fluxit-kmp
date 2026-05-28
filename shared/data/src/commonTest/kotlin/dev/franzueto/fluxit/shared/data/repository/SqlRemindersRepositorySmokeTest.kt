package dev.franzueto.fluxit.shared.data.repository

import app.cash.turbine.test
import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.fluxItDatabase
import dev.franzueto.fluxit.shared.data.db.inMemoryDriver
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlRemindersRepositorySmokeTest {
    private val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val fakeClock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    private fun repo(seed: Long = 0L): SqlRemindersRepository {
        var n = seed
        val gen =
            IdGenerator {
                n += 1
                "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
            }
        return SqlRemindersRepository(
            database = fluxItDatabase(inMemoryDriver()),
            clock = fakeClock,
            ids = gen,
            dispatcher = Dispatchers.Unconfined,
        )
    }

    private val listOwner = ReminderOwner.OfList(ListId("00000000-0000-4000-8000-aaaaaaaaaaaa"))
    private val itemOwner = ReminderOwner.OfItem(ItemId("00000000-0000-4000-8000-bbbbbbbbbbbb"))

    @Test
    fun schedule_emits_through_observeForOwner_with_recurrence_round_trip() =
        runTest {
            val r = repo()
            r.observeForOwner(listOwner).test {
                assertEquals(emptyList(), awaitItem())
                val recurrence = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
                val id =
                    (
                        r.schedule(
                            ReminderSpec(
                                owner = listOwner,
                                firesAt = fixedNow,
                                recurrence = recurrence,
                            ),
                        ) as Outcome.Ok
                    ).value
                val rows = awaitItem()
                assertEquals(1, rows.size)
                val saved = rows.single()
                assertEquals(id, saved.id)
                assertEquals(listOwner, saved.owner)
                assertEquals(recurrence, saved.recurrence)
                assertTrue(saved.isActive)
                assertNull(saved.platformHandle)
            }
        }

    @Test
    fun none_recurrence_collapses_to_null_on_the_wire_and_inflates_back() =
        runTest {
            val r = repo()
            val id =
                (
                    r.schedule(ReminderSpec(owner = itemOwner, firesAt = fixedNow)) as Outcome.Ok
                ).value
            r.observeForOwner(itemOwner).test {
                val saved = awaitItem().single()
                assertEquals(id, saved.id)
                assertEquals(RecurrenceRule.None, saved.recurrence)
            }
        }

    @Test
    fun observeForOwner_scopes_to_the_requested_owner() =
        runTest {
            val r = repo()
            r.schedule(ReminderSpec(owner = listOwner, firesAt = fixedNow))
            r.schedule(ReminderSpec(owner = itemOwner, firesAt = fixedNow))
            r.observeForOwner(listOwner).test {
                val rows = awaitItem()
                assertEquals(1, rows.size)
                assertEquals(listOwner, rows.single().owner)
            }
        }

    @Test
    fun observeUpcoming_returns_active_reminders_after_now_in_order() =
        runTest {
            val r = repo()
            val past = Instant.fromEpochMilliseconds(fixedNow.toEpochMilliseconds() - 1_000)
            val soon = Instant.fromEpochMilliseconds(fixedNow.toEpochMilliseconds() + 1_000)
            val later = Instant.fromEpochMilliseconds(fixedNow.toEpochMilliseconds() + 5_000)
            r.schedule(ReminderSpec(owner = listOwner, firesAt = past))
            val laterId = (r.schedule(ReminderSpec(owner = listOwner, firesAt = later)) as Outcome.Ok).value
            val soonId = (r.schedule(ReminderSpec(owner = listOwner, firesAt = soon)) as Outcome.Ok).value
            r.observeUpcoming(limit = 10).test {
                val rows = awaitItem()
                assertEquals(listOf(soonId, laterId), rows.map { it.id })
            }
        }

    @Test
    fun reschedule_updates_fires_at_and_recurrence() =
        runTest {
            val r = repo()
            val id =
                (
                    r.schedule(ReminderSpec(owner = listOwner, firesAt = fixedNow)) as Outcome.Ok
                ).value
            val next = Instant.fromEpochMilliseconds(fixedNow.toEpochMilliseconds() + 60_000)
            assertTrue(r.reschedule(id, firesAt = next, recurrence = RecurrenceRule.Daily) is Outcome.Ok)
            r.observeForOwner(listOwner).test {
                val saved = awaitItem().single()
                assertEquals(next, saved.firesAt)
                assertEquals(RecurrenceRule.Daily, saved.recurrence)
            }
        }

    @Test
    fun cancel_hides_the_row_and_blocks_re_cancel() =
        runTest {
            val r = repo()
            val id =
                (
                    r.schedule(ReminderSpec(owner = listOwner, firesAt = fixedNow)) as Outcome.Ok
                ).value
            assertTrue(r.cancel(id) is Outcome.Ok)
            r.observeForOwner(listOwner).test { assertEquals(emptyList(), awaitItem()) }
            assertTrue((r.cancel(id) as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun rebindPlatformHandle_round_trips_both_set_and_clear() =
        runTest {
            val r = repo()
            val id =
                (
                    r.schedule(ReminderSpec(owner = listOwner, firesAt = fixedNow)) as Outcome.Ok
                ).value
            assertTrue(r.rebindPlatformHandle(id, "wm-request-42") is Outcome.Ok)
            r.observeForOwner(listOwner).test {
                assertEquals("wm-request-42", awaitItem().single().platformHandle)
            }
            assertTrue(r.rebindPlatformHandle(id, null) is Outcome.Ok)
            r.observeForOwner(listOwner).test {
                assertNull(awaitItem().single().platformHandle)
            }
        }

    @Test
    fun reschedule_unknown_id_returns_not_found() =
        runTest {
            val r = repo()
            val out =
                r.reschedule(
                    ReminderId("00000000-0000-4000-8000-deadbeefcafe"),
                    firesAt = fixedNow,
                    recurrence = RecurrenceRule.None,
                )
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun rebindPlatformHandle_unknown_id_returns_not_found() =
        runTest {
            val r = repo()
            val out = r.rebindPlatformHandle(ReminderId("00000000-0000-4000-8000-deadbeefcafe"), "x")
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun item_owner_round_trips_via_observeForOwner() =
        runTest {
            val r = repo()
            val id =
                (
                    r.schedule(ReminderSpec(owner = itemOwner, firesAt = fixedNow)) as Outcome.Ok
                ).value
            r.observeForOwner(itemOwner).test {
                val saved = awaitItem().single()
                assertEquals(id, saved.id)
                assertNotNull(saved.owner as? ReminderOwner.OfItem)
                assertEquals(itemOwner, saved.owner)
            }
        }
}
