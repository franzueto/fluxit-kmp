package dev.franzueto.fluxit.shared.data.integration

import app.cash.turbine.test
import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.PersistentTestDb
import dev.franzueto.fluxit.shared.data.db.fluxItDatabase
import dev.franzueto.fluxit.shared.data.repository.SqlItemsRepository
import dev.franzueto.fluxit.shared.data.repository.SqlListsRepository
import dev.franzueto.fluxit.shared.data.repository.SqlRemindersRepository
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 03 §10 row (e) — the Definition-of-Done exit-criteria scenario.
 *
 * End-to-end across all three writable repositories + persistence: create
 * a list, add three items, toggle one complete, schedule a recurring
 * reminder, close the DB, reopen, observe each state matches what was
 * written. Runs on both JVM (`:shared:data:testDebugUnitTest`) and iOS
 * Sim (`:shared:data:iosSimulatorArm64Test`) via the shared
 * [PersistentTestDb] expect/actual.
 */
class IntegrationFlowTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val handle = PersistentTestDb()

    // Counter-derived UUID-v4-shaped strings so assertions can pin ids
    // across the open/close/reopen boundary. Two repository instances
    // share the same counter so id-minting stays monotonic per test.
    private var idCounter: Long = 0L
    private val ids =
        IdGenerator {
            idCounter += 1
            "00000000-0000-4000-8000-" + idCounter.toString().padStart(12, '0')
        }

    @AfterTest
    fun tearDown() {
        handle.cleanup()
    }

    private data class Session(
        val driver: app.cash.sqldelight.db.SqlDriver,
        val lists: SqlListsRepository,
        val items: SqlItemsRepository,
        val reminders: SqlRemindersRepository,
    )

    private fun openSession(): Session {
        val driver = handle.openDriver()
        val db = fluxItDatabase(driver)
        return Session(
            driver = driver,
            lists =
                SqlListsRepository(db, clock = clock, ids = ids, dispatcher = Dispatchers.Unconfined),
            items =
                SqlItemsRepository(db, clock = clock, ids = ids, dispatcher = Dispatchers.Unconfined),
            reminders =
                SqlRemindersRepository(db, clock = clock, ids = ids, dispatcher = Dispatchers.Unconfined),
        )
    }

    @Test
    fun create_add_toggle_schedule_close_reopen_state_restored() =
        runTest {
            // ── First session: write the scenario state ──────────────
            val (firstDriver, listsA, itemsA, remindersA) = openSession()

            val listId =
                (
                    listsA.create(
                        ListDraft(
                            name = "Supermarket",
                            icon = FluxItIconRef.CART,
                            color = ColorToken.PRIMARY_BLUE,
                        ),
                    ) as Outcome.Ok
                ).value
            val milkId = (itemsA.add(listId, ItemDraft("Milk")) as Outcome.Ok).value
            itemsA.add(listId, ItemDraft("Bread"))
            itemsA.add(listId, ItemDraft("Eggs"))
            assertTrue(itemsA.setCompleted(milkId, true) is Outcome.Ok)

            val recurrence = RecurrenceRule.Daily
            val reminderId =
                (
                    remindersA.schedule(
                        ReminderSpec(
                            owner = ReminderOwner.OfList(listId),
                            firesAt = now,
                            recurrence = recurrence,
                        ),
                    ) as Outcome.Ok
                ).value

            firstDriver.close()

            // ── Second session: assert everything came back ─────────
            val (secondDriver, listsB, itemsB, remindersB) = openSession()

            listsB.observe(listId).test {
                val list = assertNotNull(awaitItem())
                assertEquals("Supermarket", list.name)
                assertEquals(FluxItIconRef.CART, list.icon)
                assertEquals(ColorToken.PRIMARY_BLUE, list.color)
            }

            itemsB.observeByList(listId).test {
                val section = awaitItem()
                assertEquals(3, section.total)
                assertEquals(1, section.completedCount)
                assertEquals(listOf("Milk"), section.completed.map { it.title })
                // Newest-at-top minting → active items appear Eggs, Bread.
                assertEquals(listOf("Eggs", "Bread"), section.active.map { it.title })
            }

            remindersB.observeForOwner(ReminderOwner.OfList(listId)).test {
                val reminders = awaitItem()
                assertEquals(1, reminders.size)
                val r = reminders.single()
                assertEquals(reminderId, r.id)
                assertEquals(now, r.firesAt)
                assertEquals(RecurrenceRule.Daily, r.recurrence)
                assertTrue(r.isActive)
            }

            secondDriver.close()
        }
}
