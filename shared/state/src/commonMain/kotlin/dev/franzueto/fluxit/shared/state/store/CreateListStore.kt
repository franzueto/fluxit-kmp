package dev.franzueto.fluxit.shared.state.store

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.rule.PaletteCatalog
import dev.franzueto.fluxit.shared.domain.usecase.lists.CreateList
import dev.franzueto.fluxit.shared.domain.usecase.reminders.ScheduleReminder
import dev.franzueto.fluxit.shared.state.error.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Instant

/**
 * Store backing the Create-List sheet (Phase 09; `plan/05` §4, ADR-014).
 *
 * **Pessimistic create.** [CreateListIntent.CreateClicked] validates the name,
 * flips [CreateListState.submission] to [Submission.Submitting] (which also
 * blocks re-entry), calls [CreateList], and on success emits
 * [CreateListEffect.NavigateToListDetail] with the freshly-minted id. Unlike the
 * optimistic write-on-tap stores, nothing lands in any feed until the use case
 * confirms — there is no local row to roll back.
 *
 * **Reminder (optional).** The reminder-settings sub-screen returns a fire time +
 * recurrence via [CreateListIntent.ReminderConfigured]; it's held as a
 * [PendingReminder] (not a full [ReminderSpec]) because the [ReminderOwner] needs
 * the list id, which doesn't exist until the list is created. On a successful
 * create the reminder is scheduled best-effort against the new list
 * ([ReminderOwner.OfList]); a scheduling failure surfaces a [CreateListEffect.ShowError]
 * but does **not** block navigation — the list itself was created.
 *
 * Navigation is expressed as one-shot [effects][CreateListEffect] (§14 default).
 */
public class CreateListStore(
    scope: CoroutineScope,
    logger: AppLogger,
    private val createList: CreateList,
    private val scheduleReminder: ScheduleReminder,
) : BaseStore<CreateListState, CreateListIntent, CreateListEffect>(CreateListState(), scope, logger) {
    override suspend fun reduce(intent: CreateListIntent) {
        when (intent) {
            is CreateListIntent.NameChanged ->
                update { copy(name = intent.name, validation = validate(intent.name)) }
            is CreateListIntent.IconSelected -> update { copy(selectedIcon = intent.icon) }
            is CreateListIntent.ColorSelected -> update { copy(selectedColor = intent.color) }
            CreateListIntent.ReminderSettingsClicked -> emit(CreateListEffect.NavigateToReminderSettings)
            is CreateListIntent.ReminderConfigured ->
                update { copy(reminder = PendingReminder(intent.firesAt, intent.recurrence)) }
            CreateListIntent.CancelClicked -> emit(CreateListEffect.Dismiss)
            CreateListIntent.CreateClicked -> create()
        }
    }

    private suspend fun create() {
        val s = currentState
        // Block re-entry: a submit already in flight (or finished) is terminal.
        if (s.submission is Submission.Submitting || s.submission is Submission.Success) return
        if (s.validation != NameValidation.Valid) return

        update { copy(submission = Submission.Submitting) }
        val draft = ListDraft(name = s.name, icon = s.selectedIcon, color = s.selectedColor)
        when (val result = createList(draft)) {
            is Outcome.Ok -> {
                scheduleReminderIfAny(result.value)
                update { copy(submission = Submission.Success) }
                emit(CreateListEffect.NavigateToListDetail(result.value))
            }
            is Outcome.Err -> {
                val message = result.error.userMessage
                update { copy(submission = Submission.Error(message)) }
                emit(CreateListEffect.ShowError(message))
            }
        }
    }

    /** Best-effort: the list is already created, so a scheduler failure only warns + ShowError. */
    private suspend fun scheduleReminderIfAny(listId: ListId) {
        val pending = currentState.reminder ?: return
        val spec =
            ReminderSpec(
                owner = ReminderOwner.OfList(listId),
                firesAt = pending.firesAt,
                recurrence = pending.recurrence,
            )
        if (scheduleReminder(spec) is Outcome.Err) {
            logger.warn(TAG, "list ${listId.value} created but reminder scheduling failed")
            emit(CreateListEffect.ShowError("Your list was created, but the reminder couldn't be set."))
        }
    }

    private fun validate(raw: String): NameValidation {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> NameValidation.Empty
            trimmed.length > NAME_MAX_LEN -> NameValidation.TooLong
            else -> NameValidation.Valid
        }
    }

    private companion object {
        const val TAG = "CreateListStore"

        /**
         * Presentation-only name cap for live field feedback. `CreateList` itself
         * does not cap the name (it validates non-blank only), so this is a
         * state-layer UX bound — not a domain rule. (Diverges from §4, which lists
         * `NameValidation.TooLong` without specifying the cap.)
         */
        const val NAME_MAX_LEN = 100
    }
}

// ---- CreateListStore contract (§11: lives alongside its store). ----

public data class CreateListState(
    val name: String = "",
    val selectedIcon: FluxItIconRef = PaletteCatalog.icons.first(),
    val selectedColor: ColorToken = PaletteCatalog.colors.first(),
    val reminder: PendingReminder? = null,
    val palette: Palette = Palette(),
    val submission: Submission = Submission.Idle,
    val validation: NameValidation = NameValidation.Empty,
)

/** Icons + colors the picker offers (from [PaletteCatalog]). */
public data class Palette(
    val icons: List<FluxItIconRef> = PaletteCatalog.icons,
    val colors: List<ColorToken> = PaletteCatalog.colors,
)

/**
 * A reminder configured before the list exists. Carries only the schedule —
 * the [ReminderOwner] is bound to the new list id at create time (the list
 * doesn't exist yet when the user picks the time).
 */
public data class PendingReminder(
    val firesAt: Instant,
    val recurrence: RecurrenceRule = RecurrenceRule.None,
)

public sealed interface Submission {
    public data object Idle : Submission

    public data object Submitting : Submission

    public data object Success : Submission

    public data class Error(
        val message: String,
    ) : Submission
}

public enum class NameValidation {
    Valid,
    Empty,
    TooLong,
}

public sealed interface CreateListIntent {
    public data class NameChanged(
        val name: String,
    ) : CreateListIntent

    public data class IconSelected(
        val icon: FluxItIconRef,
    ) : CreateListIntent

    public data class ColorSelected(
        val color: ColorToken,
    ) : CreateListIntent

    public data object ReminderSettingsClicked : CreateListIntent

    public data class ReminderConfigured(
        val firesAt: Instant,
        val recurrence: RecurrenceRule = RecurrenceRule.None,
    ) : CreateListIntent

    public data object CancelClicked : CreateListIntent

    public data object CreateClicked : CreateListIntent
}

public sealed interface CreateListEffect {
    public data object Dismiss : CreateListEffect

    public data object NavigateToReminderSettings : CreateListEffect

    public data class NavigateToListDetail(
        val newListId: ListId,
    ) : CreateListEffect

    public data class ShowError(
        val message: String,
    ) : CreateListEffect
}
