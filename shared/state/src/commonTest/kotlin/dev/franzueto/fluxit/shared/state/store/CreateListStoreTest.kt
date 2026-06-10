package dev.franzueto.fluxit.shared.state.store

import app.cash.turbine.test
import dev.franzueto.fluxit.shared.domain.error.DataError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.ConfigKey
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakeConfigProvider
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeItemsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakeListsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakeRemindersRepository
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import dev.franzueto.fluxit.shared.domain.usecase.items.ObserveListDetail
import dev.franzueto.fluxit.shared.domain.usecase.lists.CreateList
import dev.franzueto.fluxit.shared.domain.usecase.lists.RenameList
import dev.franzueto.fluxit.shared.domain.usecase.lists.UpdateListAppearance
import dev.franzueto.fluxit.shared.domain.usecase.reminders.ScheduleReminder
import dev.franzueto.fluxit.shared.state.testing.StoreTestEnv
import dev.franzueto.fluxit.shared.state.testing.runStoreTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun createSeqIds(): IdGenerator {
    var n = 0
    return IdGenerator { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }
}

/** Delegating [ListsRepository] that can inject `create` / `rename` failures. */
private class FailableListsRepository(
    private val delegate: ListsRepository,
    var failCreateWith: DataError? = null,
    var failRenameWith: DataError? = null,
) : ListsRepository by delegate {
    override suspend fun create(draft: ListDraft): Outcome<ListId, DataError> =
        failCreateWith?.let { Outcome.Err(it) } ?: delegate.create(draft)

    override suspend fun rename(
        id: ListId,
        name: String,
    ): Outcome<Unit, DataError> = failRenameWith?.let { Outcome.Err(it) } ?: delegate.rename(id, name)
}

private class CreateFixture(
    val lists: FailableListsRepository,
    val reminders: FakeRemindersRepository,
    val store: CreateListStore,
)

/**
 * Edit-mode fixture: seeds one list into the fake repo (so the store's prefill
 * has something to load) and constructs the store with its id as `editingId`.
 * Returns the fixture + the seeded id.
 */
private suspend fun StoreTestEnv.editFixture(
    name: String = "Groceries",
    icon: FluxItIconRef = FluxItIconRef.entries.first(),
    color: ColorToken = ColorToken.entries.first(),
): Pair<CreateFixture, ListId> {
    val domainClock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
    val fakeLists = FakeListsRepository(ids = createSeqIds(), clock = domainClock)
    val created = fakeLists.create(ListDraft(name = name, icon = icon, color = color))
    val id = assertIs<Outcome.Ok<ListId>>(created).value
    return createFixture(editingId = id, lists = fakeLists, clock = domainClock) to id
}

private fun StoreTestEnv.createFixture(
    editingId: ListId? = null,
    config: FakeConfigProvider = FakeConfigProvider(),
    lists: FakeListsRepository? = null,
    clock: FakeClock = FakeClock(Instant.fromEpochSeconds(1_700_000_000)),
): CreateFixture {
    val failable = FailableListsRepository(lists ?: FakeListsRepository(ids = createSeqIds(), clock = clock))
    val items = FakeItemsRepository(ids = createSeqIds(), clock = clock)
    val reminders = FakeRemindersRepository(ids = createSeqIds(), clock = clock)
    val store =
        CreateListStore(
            scope = scope,
            logger = AppLogger.NoOp,
            createList = CreateList(failable),
            scheduleReminder = ScheduleReminder(reminders, FakeReminderScheduler(), clock),
            edit =
                EditListDeps(
                    ObserveListDetail(failable, items),
                    RenameList(failable),
                    UpdateListAppearance(failable),
                ),
            config = config,
            editingId = editingId,
        )
    return CreateFixture(failable, reminders, store)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CreateListStoreTest {
    @Test
    fun name_change_drives_validation() =
        runStoreTest {
            val f = createFixture()
            assertEquals(NameValidation.Empty, f.store.state.value.validation)
            f.store.dispatch(CreateListIntent.NameChanged("Groceries"))
            testScope.runCurrent()
            assertEquals(NameValidation.Valid, f.store.state.value.validation)
            f.store.dispatch(CreateListIntent.NameChanged("   "))
            testScope.runCurrent()
            assertEquals(NameValidation.Empty, f.store.state.value.validation)
            // §15 boundary cases around the 60-char cap.
            f.store.dispatch(CreateListIntent.NameChanged("x".repeat(60)))
            testScope.runCurrent()
            assertEquals(NameValidation.Valid, f.store.state.value.validation)
            f.store.dispatch(CreateListIntent.NameChanged("x".repeat(61)))
            testScope.runCurrent()
            assertEquals(NameValidation.TooLong, f.store.state.value.validation)
        }

    @Test
    fun valid_create_navigates_to_detail_and_marks_success() =
        runStoreTest {
            val f = createFixture()
            f.store.dispatch(CreateListIntent.NameChanged("Groceries"))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CreateClicked)
                val effect = assertIs<CreateListEffect.NavigateToListDetail>(awaitItem())
                assertTrue(effect.newListId.value.isNotEmpty())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(Submission.Success, f.store.state.value.submission)
        }

    @Test
    fun create_is_blocked_when_name_invalid() =
        runStoreTest {
            val f = createFixture()
            // No NameChanged → validation stays Empty.
            f.store.dispatch(CreateListIntent.CreateClicked)
            testScope.runCurrent()
            assertEquals(Submission.Idle, f.store.state.value.submission)
        }

    @Test
    fun create_failure_sets_error_submission_and_emits_show_error() =
        runStoreTest {
            val f = createFixture()
            f.lists.failCreateWith = DataError.Storage(RuntimeException("disk full"))
            f.store.dispatch(CreateListIntent.NameChanged("Groceries"))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CreateClicked)
                assertIs<CreateListEffect.ShowError>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertIs<Submission.Error>(f.store.state.value.submission)
        }

    @Test
    fun configured_reminder_is_scheduled_against_the_new_list() =
        runStoreTest {
            val f = createFixture()
            f.store.dispatch(CreateListIntent.NameChanged("Groceries"))
            // Fires comfortably in the future relative to the domain clock.
            f.store.dispatch(CreateListIntent.ReminderConfigured(Instant.fromEpochSeconds(1_800_000_000)))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CreateClicked)
                val effect = assertIs<CreateListEffect.NavigateToListDetail>(awaitItem())
                // A reminder row now exists for the created list.
                f.reminders.observeForOwner(ReminderOwner.OfList(effect.newListId)).test {
                    assertTrue(awaitItem().isNotEmpty())
                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun icon_and_color_selection_update_state() =
        runStoreTest {
            val f = createFixture()
            f.store.dispatch(CreateListIntent.IconSelected(FluxItIconRef.STAR))
            f.store.dispatch(CreateListIntent.ColorSelected(ColorToken.ACCENT_ORANGE))
            testScope.runCurrent()
            assertEquals(FluxItIconRef.STAR, f.store.state.value.selectedIcon)
            assertEquals(ColorToken.ACCENT_ORANGE, f.store.state.value.selectedColor)
        }

    @Test
    fun reminder_settings_and_cancel_emit_navigation_effects() =
        runStoreTest {
            val f = createFixture()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.ReminderSettingsClicked)
                assertEquals(CreateListEffect.NavigateToReminderSettings, awaitItem())
                f.store.dispatch(CreateListIntent.CancelClicked)
                assertEquals(CreateListEffect.Dismiss, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun second_create_after_success_is_blocked() =
        runStoreTest {
            val f = createFixture()
            f.store.dispatch(CreateListIntent.NameChanged("Groceries"))
            testScope.runCurrent()
            f.store.dispatch(CreateListIntent.CreateClicked)
            testScope.runCurrent()
            assertEquals(Submission.Success, f.store.state.value.submission)
            // A second submit is terminal — the guard returns before re-running create.
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CreateClicked)
                testScope.runCurrent()
                expectNoEvents()
            }
            assertEquals(Submission.Success, f.store.state.value.submission)
        }

    @Test
    fun created_list_with_unschedulable_reminder_navigates_but_warns() =
        runStoreTest {
            val f = createFixture()
            f.store.dispatch(CreateListIntent.NameChanged("Groceries"))
            // A fire time in the PAST → ScheduleReminder rejects it; the list is
            // still created, so navigation proceeds after a best-effort ShowError.
            f.store.dispatch(CreateListIntent.ReminderConfigured(Instant.fromEpochSeconds(1_600_000_000)))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CreateClicked)
                assertIs<CreateListEffect.ShowError>(awaitItem())
                assertIs<CreateListEffect.NavigateToListDetail>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(Submission.Success, f.store.state.value.submission)
        }

    // ---- Phase 09 backfill: validation visibility (§4) ----

    @Test
    fun invalid_submit_reveals_validation_instead_of_submitting() =
        runStoreTest {
            val f = createFixture()
            assertFalse(f.store.state.value.validationVisible)
            f.store.dispatch(CreateListIntent.CreateClicked)
            testScope.runCurrent()
            assertTrue(f.store.state.value.validationVisible)
            assertEquals(Submission.Idle, f.store.state.value.submission)
        }

    @Test
    fun name_blur_reveals_validation() =
        runStoreTest {
            val f = createFixture()
            f.store.dispatch(CreateListIntent.NameBlurred)
            testScope.runCurrent()
            assertTrue(f.store.state.value.validationVisible)
        }

    // ---- Phase 09 backfill: cancel / discard (§6) ----

    @Test
    fun cancel_on_dirty_form_asks_for_confirmation_then_discard_dismisses() =
        runStoreTest {
            val f = createFixture()
            f.store.dispatch(CreateListIntent.NameChanged("Groceries"))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CancelClicked)
                assertEquals(CreateListEffect.ConfirmDiscard, awaitItem())
                f.store.dispatch(CreateListIntent.DiscardConfirmed)
                assertEquals(CreateListEffect.Dismiss, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun non_default_icon_or_color_makes_create_form_dirty() =
        runStoreTest {
            val f = createFixture()
            f.store.dispatch(CreateListIntent.IconSelected(FluxItIconRef.STAR))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CancelClicked)
                assertEquals(CreateListEffect.ConfirmDiscard, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- Phase 09 backfill: reminder editor flag (§8) ----

    @Test
    fun reminder_editor_flag_defaults_off_and_respects_override() =
        runStoreTest {
            assertFalse(
                createFixture()
                    .store.state.value.reminderEditorEnabled,
            )
            val enabled = FakeConfigProvider(mapOf(ConfigKey.RemindersEditorEnabled to true))
            assertTrue(
                createFixture(config = enabled)
                    .store.state.value.reminderEditorEnabled,
            )
        }

    // ---- Phase 09 backfill: edit mode (§9) ----

    @Test
    fun edit_mode_prefills_from_the_list_being_edited() =
        runStoreTest {
            val (f, _) = editFixture(name = "Trip", icon = FluxItIconRef.STAR, color = ColorToken.ACCENT_ORANGE)
            testScope.runCurrent()
            val s = f.store.state.value
            assertTrue(s.editing)
            assertEquals("Trip", s.name)
            assertEquals(FluxItIconRef.STAR, s.selectedIcon)
            assertEquals(ColorToken.ACCENT_ORANGE, s.selectedColor)
            assertEquals(NameValidation.Valid, s.validation)
        }

    @Test
    fun edit_mode_with_missing_list_dismisses() =
        runStoreTest {
            val f = createFixture(editingId = ListId("nope"))
            f.store.effects.test {
                assertEquals(CreateListEffect.Dismiss, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun edit_save_persists_rename_and_appearance_then_dismisses() =
        runStoreTest {
            val (f, id) = editFixture()
            testScope.runCurrent()
            f.store.dispatch(CreateListIntent.NameChanged("Renamed"))
            f.store.dispatch(CreateListIntent.IconSelected(FluxItIconRef.STAR))
            f.store.dispatch(CreateListIntent.ColorSelected(ColorToken.ACCENT_ORANGE))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CreateClicked)
                assertEquals(CreateListEffect.Dismiss, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(Submission.Success, f.store.state.value.submission)
            f.lists.observe(id).test {
                val detail = awaitItem()
                assertEquals("Renamed", detail?.name)
                assertEquals(FluxItIconRef.STAR, detail?.icon)
                assertEquals(ColorToken.ACCENT_ORANGE, detail?.color)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun edit_save_with_no_changes_is_a_no_op_success() =
        runStoreTest {
            val (f, _) = editFixture()
            testScope.runCurrent()
            // Force any rename to fail: a no-change save must never call rename.
            f.lists.failRenameWith = DataError.Storage(RuntimeException("must not be called"))
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CreateClicked)
                assertEquals(CreateListEffect.Dismiss, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(Submission.Success, f.store.state.value.submission)
        }

    @Test
    fun edit_save_failure_sets_error_and_keeps_modal_open() =
        runStoreTest {
            val (f, _) = editFixture()
            testScope.runCurrent()
            f.lists.failRenameWith = DataError.Storage(RuntimeException("disk full"))
            f.store.dispatch(CreateListIntent.NameChanged("Renamed"))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CreateClicked)
                assertIs<CreateListEffect.ShowError>(awaitItem())
                expectNoEvents()
            }
            assertIs<Submission.Error>(f.store.state.value.submission)
        }

    @Test
    fun edit_cancel_with_no_changes_dismisses_without_confirm() =
        runStoreTest {
            val (f, _) = editFixture()
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CancelClicked)
                assertEquals(CreateListEffect.Dismiss, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun edit_cancel_after_a_change_asks_for_confirmation() =
        runStoreTest {
            val (f, _) = editFixture()
            testScope.runCurrent()
            f.store.dispatch(CreateListIntent.NameChanged("Renamed"))
            testScope.runCurrent()
            f.store.effects.test {
                f.store.dispatch(CreateListIntent.CancelClicked)
                assertEquals(CreateListEffect.ConfirmDiscard, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
