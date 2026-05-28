package dev.franzueto.fluxit.shared.data.repository

import dev.franzueto.fluxit.core.utils.IdGenerator
import dev.franzueto.fluxit.shared.data.db.FluxItDatabase
import dev.franzueto.fluxit.shared.data.db.fluxItDatabase
import dev.franzueto.fluxit.shared.data.db.inMemoryDriver
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 03 §10 row (c) — repository error paths the existing smoke tests
 * don't cover. Two error categories:
 *
 *   1. **NotFound** on writes targeting an unknown / tombstoned id. The
 *      smoke tests cover this for create/rename/delete/cancel; this file
 *      fills in setStarred, updateAppearance, setCompleted, and the
 *      reorder methods.
 *
 *   2. **FK violation** from `item.photo_id REFERENCES photo(id)` when
 *      the photo doesn't exist. Surfaces as `DataError.Storage` today —
 *      the repository's `guard{}` maps every driver exception to that
 *      variant. Promoting FK-specific exceptions to `DataError.Conflict`
 *      would need a cross-platform exception-type discriminator (the
 *      `DataError` taxonomy has the slot ready); leaving as a follow-up
 *      until SQLDelight surfaces a stable exception hierarchy. Test pins
 *      the current behavior so the upgrade lands with a deliberate
 *      assertion flip.
 *
 * The FK tests rely on `PRAGMA foreign_keys = ON` being set by both
 * `TestDrivers.android.kt` and `TestDrivers.ios.kt` — without that the
 * schema's FK declarations are inert and these tests would silently pass
 * for the wrong reason.
 */
class RepositoryErrorPathTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private data class Fixture(
        val db: FluxItDatabase,
        val lists: SqlListsRepository,
        val items: SqlItemsRepository,
    )

    private fun fixture(): Fixture {
        var n = 0L
        val ids =
            IdGenerator {
                n += 1
                "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
            }
        val db = fluxItDatabase(inMemoryDriver())
        return Fixture(
            db = db,
            lists = SqlListsRepository(db, clock, ids, Dispatchers.Unconfined),
            items = SqlItemsRepository(db, clock, ids, Dispatchers.Unconfined),
        )
    }

    private val unknownListId = ListId("00000000-0000-4000-8000-deadbeefcafe")
    private val unknownItemId = ItemId("00000000-0000-4000-8000-deadbeefcafe")
    private val bogusPhotoId = PhotoId("00000000-0000-4000-8000-000000beefff")

    // ───────────────────── NotFound paths ─────────────────────

    @Test
    fun lists_updateAppearance_unknown_id_returns_not_found() =
        runTest {
            val out =
                fixture().lists.updateAppearance(
                    unknownListId,
                    icon = FluxItIconRef.HOME,
                    color = ColorToken.ACCENT_ROSE,
                )
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun lists_setStarred_unknown_id_returns_not_found() =
        runTest {
            val out = fixture().lists.setStarred(unknownListId, starred = true)
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun lists_reorder_unknown_id_returns_not_found() =
        runTest {
            val out = fixture().lists.reorder(unknownListId, previous = null, next = null)
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun items_setCompleted_unknown_id_returns_not_found() =
        runTest {
            val out = fixture().items.setCompleted(unknownItemId, completed = true)
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun items_setStarred_unknown_id_returns_not_found() =
        runTest {
            val out = fixture().items.setStarred(unknownItemId, starred = true)
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    @Test
    fun items_reorder_unknown_id_returns_not_found() =
        runTest {
            val out = fixture().items.reorder(unknownItemId, previous = null, next = null)
            assertTrue((out as Outcome.Err).error is DataError.NotFound)
        }

    // ───────────────────── FK violation paths ─────────────────────

    @Test
    fun items_add_with_unknown_photoId_surfaces_as_storage_error() =
        runTest {
            val fx = fixture()
            val listId =
                (
                    fx.lists.create(
                        ListDraft(name = "L", icon = FluxItIconRef.CART, color = ColorToken.PRIMARY_BLUE),
                    ) as Outcome.Ok
                ).value

            val out = fx.items.add(listId, ItemDraft(title = "Milk", photoId = bogusPhotoId))
            // Current behavior: every driver exception → DataError.Storage.
            // When SQLDelight grows a stable cross-platform exception
            // discriminator, promote this assertion to DataError.Conflict
            // — the taxonomy slot is already there.
            assertTrue(
                (out as Outcome.Err).error is DataError.Storage,
                "expected Storage (FK violation); got ${out.error}",
            )
        }

    @Test
    fun items_update_with_unknown_photoId_surfaces_as_storage_error() =
        runTest {
            val fx = fixture()
            val listId =
                (
                    fx.lists.create(
                        ListDraft(name = "L", icon = FluxItIconRef.CART, color = ColorToken.PRIMARY_BLUE),
                    ) as Outcome.Ok
                ).value
            val itemId = (fx.items.add(listId, ItemDraft("Milk")) as Outcome.Ok).value

            val out =
                fx.items.update(
                    itemId,
                    ItemPatch(title = "Milk", subtitle = null, description = null, photoId = bogusPhotoId),
                )
            assertTrue((out as Outcome.Err).error is DataError.Storage)
        }
}
