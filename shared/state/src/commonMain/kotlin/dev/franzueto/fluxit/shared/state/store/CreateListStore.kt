package dev.franzueto.fluxit.shared.state.store

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListDraft
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.RecurrenceRule
import dev.franzueto.fluxit.shared.domain.model.ReminderOwner
import dev.franzueto.fluxit.shared.domain.model.ReminderSpec
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.ConfigKey
import dev.franzueto.fluxit.shared.domain.port.ConfigProvider
import dev.franzueto.fluxit.shared.domain.rule.PaletteCatalog
import dev.franzueto.fluxit.shared.domain.usecase.items.ObserveListDetail
import dev.franzueto.fluxit.shared.domain.usecase.lists.CreateList
import dev.franzueto.fluxit.shared.domain.usecase.lists.RenameList
import dev.franzueto.fluxit.shared.domain.usecase.lists.UpdateListAppearance
import dev.franzueto.fluxit.shared.domain.usecase.reminders.ScheduleReminder
import dev.franzueto.fluxit.shared.state.error.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Store backing the Create-List sheet (Phase 09; `plan/05` §4, ADR-014) — and,
 * since the Phase 09 backfill, the **Edit-List** flow over the same screen
 * (`plan/09` §9): construct with a non-null [editingId] and the store prefills
 * name/icon/color from the live list (via [EditListDeps.observeListDetail],
 * first emission only) and submits through [RenameList] + [UpdateListAppearance]
 * instead of [CreateList].
 *
 * **Pessimistic create.** [CreateListIntent.CreateClicked] validates the name,
 * flips [CreateListState.submission] to [Submission.Submitting] (which also
 * blocks re-entry), calls [CreateList], and on success emits
 * [CreateListEffect.NavigateToListDetail] with the freshly-minted id. Unlike the
 * optimistic write-on-tap stores, nothing lands in any feed until the use case
 * confirms — there is no local row to roll back.
 *
 * **Edit submit.** The same [CreateListIntent.CreateClicked] drives edit mode
 * (no `SaveClicked` alias — `plan/09` §3 divergence): rename runs only when the
 * trimmed name changed, appearance only when icon/color changed; a no-change
 * save is an immediate success. Success emits [CreateListEffect.Dismiss] (the
 * user came from the detail screen, which re-observes) — never
 * `NavigateToListDetail`.
 *
 * **Validation visibility (`plan/09` §4).** [CreateListState.validation] is live
 * from the first keystroke, but the UI should render errors only once
 * [CreateListState.validationVisible] is true — set by the first
 * [CreateListIntent.NameBlurred] or by a submit attempt with an invalid name.
 *
 * **Cancel / discard (`plan/09` §6).** [CreateListIntent.CancelClicked] emits
 * [CreateListEffect.Dismiss] when the form is pristine, else
 * [CreateListEffect.ConfirmDiscard]; the UI's "Discard" choice comes back as
 * [CreateListIntent.DiscardConfirmed]. Create-mode dirty = any field moved off
 * its default; edit-mode dirty = differs from the prefilled snapshot.
 *
 * **Reminder (optional).** The reminder-settings sub-screen returns a fire time +
 * recurrence via [CreateListIntent.ReminderConfigured]; it's held as a
 * [PendingReminder] (not a full [ReminderSpec]) because the [ReminderOwner] needs
 * the list id, which doesn't exist until the list is created. On a successful
 * create the reminder is scheduled best-effort against the new list
 * ([ReminderOwner.OfList]); a scheduling failure surfaces a [CreateListEffect.ShowError]
 * but does **not** block navigation — the list itself was created. The editor
 * entry point itself is gated by [ConfigKey.RemindersEditorEnabled] (off in v1
 * until Phase 13 ships the editor), surfaced as
 * [CreateListState.reminderEditorEnabled].
 *
 * Navigation is expressed as one-shot [effects][CreateListEffect] (§14 default).
 */
public class CreateListStore(
    scope: CoroutineScope,
    logger: AppLogger,
    private val createList: CreateList,
    private val scheduleReminder: ScheduleReminder,
    private val edit: EditListDeps,
    config: ConfigProvider,
    private val editingId: ListId? = null,
) : BaseStore<CreateListState, CreateListIntent, CreateListEffect>(
        CreateListState(
            editing = editingId != null,
            reminderEditorEnabled = config.get(ConfigKey.RemindersEditorEnabled),
        ),
        scope,
        logger,
    ) {
    /**
     * Edit-mode snapshot of the list as prefilled — the baseline for the §6
     * dirty compare and the change-detection in [saveEdits]. Null until the
     * prefill lands (and always null in create mode).
     */
    private var original: ListDetail? = null

    init {
        if (editingId != null) {
            scope.launch { prefill(editingId) }
        }
    }

    override suspend fun reduce(intent: CreateListIntent) {
        when (intent) {
            is CreateListIntent.NameChanged ->
                update { copy(name = intent.name, validation = validate(intent.name)) }
            CreateListIntent.NameBlurred -> update { copy(validationVisible = true) }
            is CreateListIntent.IconSelected -> update { copy(selectedIcon = intent.icon) }
            is CreateListIntent.ColorSelected -> update { copy(selectedColor = intent.color) }
            CreateListIntent.ReminderSettingsClicked -> emit(CreateListEffect.NavigateToReminderSettings)
            is CreateListIntent.ReminderConfigured ->
                update { copy(reminder = PendingReminder(intent.firesAt, intent.recurrence)) }
            CreateListIntent.CancelClicked ->
                emit(if (isDirty()) CreateListEffect.ConfirmDiscard else CreateListEffect.Dismiss)
            CreateListIntent.DiscardConfirmed -> emit(CreateListEffect.Dismiss)
            CreateListIntent.CreateClicked -> submit()
        }
    }

    /**
     * Prefill edit mode from the first live emission. A null detail means the
     * list is gone (soft-deleted between menu tap and screen entry) — nothing
     * to edit, so the modal just dismisses.
     */
    private suspend fun prefill(id: ListId) {
        val detail = edit.observeListDetail(id).first().detail
        if (detail == null) {
            logger.warn(TAG, "list ${id.value} gone before edit prefill; dismissing")
            emit(CreateListEffect.Dismiss)
            return
        }
        original = detail
        update {
            copy(
                name = detail.name,
                selectedIcon = detail.icon,
                selectedColor = detail.color,
                validation = validate(detail.name),
            )
        }
    }

    private fun isDirty(): Boolean {
        val s = currentState
        val orig = original
        return when {
            editingId == null ->
                s.name.isNotEmpty() ||
                    s.selectedIcon != PaletteCatalog.icons.first() ||
                    s.selectedColor != PaletteCatalog.colors.first() ||
                    s.reminder != null
            // Prefill not landed yet → nothing the user could have touched counts.
            orig == null -> false
            else ->
                s.name.trim() != orig.name ||
                    s.selectedIcon != orig.icon ||
                    s.selectedColor != orig.color
        }
    }

    private suspend fun submit() {
        val s = currentState
        // Block re-entry: a submit already in flight (or finished) is terminal.
        if (s.submission is Submission.Submitting || s.submission is Submission.Success) return
        if (s.validation != NameValidation.Valid) {
            // §4: a blocked submit is what reveals the inline error.
            update { copy(validationVisible = true) }
            return
        }
        if (editingId != null) save(editingId) else create(s)
    }

    private suspend fun create(s: CreateListState) {
        update { copy(submission = Submission.Submitting) }
        val draft = ListDraft(name = s.name, icon = s.selectedIcon, color = s.selectedColor)
        when (val result = createList(draft)) {
            is Outcome.Ok -> {
                scheduleReminderIfAny(result.value)
                update { copy(submission = Submission.Success) }
                emit(CreateListEffect.NavigateToListDetail(result.value))
            }
            is Outcome.Err -> fail(result.error)
        }
    }

    private suspend fun save(id: ListId) {
        // Prefill hasn't landed — the user can't have edited anything meaningful yet.
        val orig = original ?: return
        update { copy(submission = Submission.Submitting) }
        when (val result = saveEdits(id, orig)) {
            is Outcome.Ok -> {
                update { copy(submission = Submission.Success) }
                emit(CreateListEffect.Dismiss)
            }
            is Outcome.Err -> fail(result.error)
        }
    }

    /** Persist only what changed against [orig]; first failure short-circuits. */
    private suspend fun saveEdits(
        id: ListId,
        orig: ListDetail,
    ): Outcome<Unit, DomainError> {
        val s = currentState
        if (s.name.trim() != orig.name) {
            val renamed = edit.renameList(id, s.name)
            if (renamed is Outcome.Err) return renamed
        }
        if (s.selectedIcon != orig.icon || s.selectedColor != orig.color) {
            return edit.updateListAppearance(id, s.selectedIcon, s.selectedColor)
        }
        return Outcome.Ok(Unit)
    }

    private suspend fun fail(error: DomainError) {
        val message = error.userMessage
        update { copy(submission = Submission.Error(message)) }
        emit(CreateListEffect.ShowError(message))
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
         * state-layer UX bound — not a domain rule. 60 per the `plan/09` §2/§4
         * locked cap (the Phase 05 interim value was 100).
         */
        const val NAME_MAX_LEN = 60
    }
}

/**
 * The edit-mode use-case trio, bundled so [CreateListStore]'s constructor stays
 * under the detekt parameter cap (cf. `ListDetailChrome`): prefill via
 * [observeListDetail] (first emission), persist via [renameList] +
 * [updateListAppearance].
 */
public data class EditListDeps(
    val observeListDetail: ObserveListDetail,
    val renameList: RenameList,
    val updateListAppearance: UpdateListAppearance,
)

// ---- CreateListStore contract (§11: lives alongside its store). ----

public data class CreateListState(
    val name: String = "",
    val selectedIcon: FluxItIconRef = PaletteCatalog.icons.first(),
    val selectedColor: ColorToken = PaletteCatalog.colors.first(),
    val reminder: PendingReminder? = null,
    val palette: Palette = Palette(),
    val submission: Submission = Submission.Idle,
    val validation: NameValidation = NameValidation.Empty,
    /** True when the store was constructed with an `editingId` (`plan/09` §9). */
    val editing: Boolean = false,
    /** §4: render inline name errors only once true (first blur or submit attempt). */
    val validationVisible: Boolean = false,
    /** [ConfigKey.RemindersEditorEnabled] — the §8 row is disabled while false. */
    val reminderEditorEnabled: Boolean = false,
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

    /** First focus-loss on the name field reveals validation (§4). */
    public data object NameBlurred : CreateListIntent

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

    /** The user chose "Discard" in the §6 confirm-discard alert. */
    public data object DiscardConfirmed : CreateListIntent

    /** Submit (both modes — labelled "Save" in edit mode, no separate intent). */
    public data object CreateClicked : CreateListIntent
}

public sealed interface CreateListEffect {
    public data object Dismiss : CreateListEffect

    /** §6: the form is dirty — UI shows the "Discard changes?" alert. */
    public data object ConfirmDiscard : CreateListEffect

    public data object NavigateToReminderSettings : CreateListEffect

    public data class NavigateToListDetail(
        val newListId: ListId,
    ) : CreateListEffect

    public data class ShowError(
        val message: String,
    ) : CreateListEffect
}
