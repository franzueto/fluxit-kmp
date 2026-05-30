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
import dev.franzueto.fluxit.shared.domain.port.FakeClock
import dev.franzueto.fluxit.shared.domain.port.FakeReminderScheduler
import dev.franzueto.fluxit.shared.domain.port.IdGenerator
import dev.franzueto.fluxit.shared.domain.repository.FakeListsRepository
import dev.franzueto.fluxit.shared.domain.repository.FakeRemindersRepository
import dev.franzueto.fluxit.shared.domain.repository.ListsRepository
import dev.franzueto.fluxit.shared.domain.usecase.lists.CreateList
import dev.franzueto.fluxit.shared.domain.usecase.reminders.ScheduleReminder
import dev.franzueto.fluxit.shared.state.testing.StoreTestEnv
import dev.franzueto.fluxit.shared.state.testing.runStoreTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun createSeqIds(): IdGenerator {
    var n = 0
    return IdGenerator { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }
}

/** Delegating [ListsRepository] that can inject a `create` failure. */
private class FailableListsRepository(
    private val delegate: ListsRepository,
    var failCreateWith: DataError? = null,
) : ListsRepository by delegate {
    override suspend fun create(draft: ListDraft): Outcome<ListId, DataError> =
        failCreateWith?.let { Outcome.Err(it) } ?: delegate.create(draft)
}

private class CreateFixture(
    val lists: FailableListsRepository,
    val reminders: FakeRemindersRepository,
    val store: CreateListStore,
)

private fun StoreTestEnv.createFixture(): CreateFixture {
    val domainClock = FakeClock(Instant.fromEpochSeconds(1_700_000_000))
    val lists = FailableListsRepository(FakeListsRepository(ids = createSeqIds(), clock = domainClock))
    val reminders = FakeRemindersRepository(ids = createSeqIds(), clock = domainClock)
    val store =
        CreateListStore(
            scope = scope,
            logger = AppLogger.NoOp,
            createList = CreateList(lists),
            scheduleReminder = ScheduleReminder(reminders, FakeReminderScheduler(), domainClock),
        )
    return CreateFixture(lists, reminders, store)
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
            f.store.dispatch(CreateListIntent.NameChanged("x".repeat(101)))
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
}
