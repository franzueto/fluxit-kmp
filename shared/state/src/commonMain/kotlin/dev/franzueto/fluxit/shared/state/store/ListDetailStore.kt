package dev.franzueto.fluxit.shared.state.store

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemDraft
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemsSection
import dev.franzueto.fluxit.shared.domain.model.ListDetail
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.usecase.items.AddItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ClearCompletedItems
import dev.franzueto.fluxit.shared.domain.usecase.items.DeleteItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ObserveListDetail
import dev.franzueto.fluxit.shared.domain.usecase.items.ToggleItemCompleted
import dev.franzueto.fluxit.shared.state.error.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Store backing the list-detail screen (Phase 08; `plan/05` §4/§5/§6, ADR-014).
 *
 * Composes the items use cases over a single reactive source:
 * - **Feed.** [ListDetailIntent.Init] launches [ObserveListDetail], whose one
 *   combined emission carries both the header ([ListDetail]?) and the partitioned
 *   [ItemsSection]; it is split into [ListDetailState.header] / [sections]. A
 *   `null` header means the list is gone (soft-deleted / never existed) → an
 *   [LoadState.Error] rather than [LoadState.Empty] (Empty reads as "no items",
 *   which is wrong for a vanished list).
 * - **Optimistic completion toggle (§5).** [ListDetailIntent.ItemCompletionToggled]
 *   moves the item between the active/completed partitions in state immediately,
 *   calls [ToggleItemCompleted], and reverts + emits [ListDetailEffect.ShowError]
 *   on failure. The live feed re-emits the authoritative partition shortly after
 *   and supersedes the optimistic bridge.
 * - **Optimistic delete + undo (§6).** [ListDetailIntent.ItemDeleteClicked] removes
 *   the item from state, calls [DeleteItem], and opens a 5s undo window
 *   ([ListDetailState.pendingDelete] + a timer self-dispatching
 *   [ListDetailIntent.UndoWindowExpired]). Same mechanics as `ListsDashboardStore`.
 *
 * **Per-item undo restore is data-layer-blocked.** There is no `UndoDeleteItem`
 * use case — the shipped `ItemsRepository` exposes no `deleted_at = NULL` restore
 * primitive (see `DeleteItem`'s KDoc). [ListDetailIntent.UndoItemDeleteClicked]
 * therefore only dismisses the snackbar; the soft-delete stands. Likewise
 * [ListDetailIntent.ClearCompletedClicked] has no bulk-undo (the `RestoreItems` /
 * `ClearCompletedItems → List<ItemId>` variant is the same deferral) — it relies on
 * the feed to reconcile and surfaces only failures.
 *
 * **Composer-on-failure (§14 default): keep text + inline error.** A failed
 * [ListDetailIntent.ComposerSubmit] leaves [ListDetailState.composerText] intact
 * and sets [ListDetailState.composerError] (an inline field rather than a transient
 * snackbar effect — diverges from §4's state sketch, which omits it).
 *
 * Navigation is expressed as one-shot [effects][ListDetailEffect] (§14 default).
 */
public class ListDetailStore(
    private val scope: CoroutineScope,
    logger: AppLogger,
    private val observeListDetail: ObserveListDetail,
    private val toggleItemCompleted: ToggleItemCompleted,
    private val addItem: AddItem,
    private val deleteItem: DeleteItem,
    private val clearCompletedItems: ClearCompletedItems,
    private val clock: Clock,
) : BaseStore<ListDetailState, ListDetailIntent, ListDetailEffect>(ListDetailState(), scope, logger) {
    /** Set once by [ListDetailIntent.Init]; needed by `AddItem` / `ClearCompletedItems`. */
    private var listId: ListId? = null

    /** Guards against a second [ListDetailIntent.Init] re-subscribing the feed. */
    private var feedJob: Job? = null

    /** The in-flight undo-window timer, if any; cancelled on undo / re-delete / expiry. */
    private var undoTimer: Job? = null

    override suspend fun reduce(intent: ListDetailIntent) {
        when (intent) {
            is ListDetailIntent.Init -> init(intent.listId)
            ListDetailIntent.BackClicked -> emit(ListDetailEffect.NavigateBack)
            ListDetailIntent.MoreClicked -> emit(ListDetailEffect.OpenListMenu)
            ListDetailIntent.ToggleShowCompleted -> update { copy(showCompleted = !showCompleted) }
            is ListDetailIntent.ItemTapped -> emit(ListDetailEffect.NavigateToEditItem(intent.id))
            is ListDetailIntent.ItemCompletionToggled -> toggleCompletion(intent.id)
            is ListDetailIntent.ItemDeleteClicked -> delete(intent.id)
            ListDetailIntent.UndoItemDeleteClicked -> undoDelete()
            is ListDetailIntent.UndoWindowExpired -> expireUndoWindow(intent.id)
            is ListDetailIntent.ComposerTextChanged ->
                update { copy(composerText = intent.text, composerError = null) }
            ListDetailIntent.ComposerSubmit -> submitComposer()
            ListDetailIntent.ClearCompletedClicked -> clearCompleted()
        }
    }

    private fun init(id: ListId) {
        if (feedJob != null) return
        listId = id
        feedJob =
            scope.launch {
                observeListDetail(id)
                    .catch { cause ->
                        logger.warn(TAG, "list-detail feed failed: ${cause.message}")
                        update {
                            copy(
                                header = LoadState.Error("Something went wrong loading this list."),
                                sections = LoadState.Error("Something went wrong loading this list."),
                            )
                        }
                    }.collect { view ->
                        update {
                            copy(
                                header =
                                    view.detail
                                        ?.let { LoadState.Loaded(it) }
                                        ?: LoadState.Error("This list is no longer available."),
                                sections = view.items.toLoadState(),
                            )
                        }
                    }
            }
    }

    private suspend fun toggleCompletion(id: ItemId) {
        val section = (currentState.sections as? LoadState.Loaded)?.value ?: return
        optimistic(
            apply = { copy(sections = LoadState.Loaded(section.toggling(id))) },
            revert = { copy(sections = LoadState.Loaded(section)) },
            op = { toggleItemCompleted(id) },
            onError = { emit(ListDetailEffect.ShowError(it.userMessage)) },
        )
    }

    private suspend fun submitComposer() {
        val id = listId ?: return
        val text = currentState.composerText
        if (text.isBlank()) return
        when (val result = addItem(id, ItemDraft(title = text))) {
            is Outcome.Ok -> update { copy(composerText = "", composerError = null) }
            // §14 default: keep the text, surface the error inline (not a snackbar).
            is Outcome.Err -> update { copy(composerError = result.error.userMessage) }
        }
    }

    private suspend fun delete(id: ItemId) {
        // Rapid second delete: finalize the still-open window first (the prior
        // soft-delete already persisted), then open a fresh window for this delete.
        if (currentState.pendingDelete != null) finalizePendingDelete()

        val section = (currentState.sections as? LoadState.Loaded)?.value
        val title = section?.find(id)?.title
        val result =
            optimistic(
                apply = { copy(sections = removeItem(id)) },
                revert = { copy(sections = section?.let { LoadState.Loaded(it) } ?: sections) },
                op = { deleteItem(id) },
                onError = { emit(ListDetailEffect.ShowError(it.userMessage)) },
            )
        if (result is Outcome.Ok) {
            val expiresAt = clock.now().plus(UNDO_WINDOW)
            update { copy(pendingDelete = PendingItemDelete(id, expiresAt)) }
            emit(ListDetailEffect.ShowUndoSnackbar(title ?: "Item", UNDO_WINDOW.inWholeSeconds.toInt()))
            startUndoTimer(id)
        }
    }

    private suspend fun clearCompleted() {
        val id = listId ?: return
        // No bulk-undo: ClearCompletedItems returns a count, not the ids, and there
        // is no RestoreItems primitive (same data-layer deferral as per-item undo).
        // The feed reconciles the cleared rows; we surface only failures.
        when (val result = clearCompletedItems(id)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> emit(ListDetailEffect.ShowError(result.error.userMessage))
        }
    }

    private fun startUndoTimer(id: ItemId) {
        undoTimer?.cancel()
        undoTimer =
            scope.launch {
                delay(UNDO_WINDOW)
                dispatch(ListDetailIntent.UndoWindowExpired(id))
            }
    }

    private fun expireUndoWindow(id: ItemId) {
        // Only clear if the window still belongs to this delete — a re-delete may
        // have already finalized it and opened a new one. No use-case call (§6):
        // the soft-delete is permanent; we merely retire the snackbar.
        if (currentState.pendingDelete?.id == id) {
            undoTimer?.cancel()
            update { copy(pendingDelete = null) }
        }
    }

    private fun undoDelete() {
        val pending = currentState.pendingDelete ?: return
        finalizePendingDelete()
        // TODO(data-layer): call UndoDeleteItem(pending.id) once a `deleted_at = NULL`
        // restore primitive lands (blocked today — see DeleteItem KDoc). Until then the
        // soft-delete stands and a live feed emission would clobber any optimistic local
        // re-insert, so we only dismiss the snackbar rather than fake a restore.
        logger.warn(TAG, "undo requested for ${pending.id.value} but UndoDeleteItem is not yet available; delete stands")
    }

    /** Cancel the undo timer and clear [ListDetailState.pendingDelete]. */
    private fun finalizePendingDelete() {
        undoTimer?.cancel()
        undoTimer = null
        update { copy(pendingDelete = null) }
    }

    private fun ListDetailState.removeItem(id: ItemId): LoadState<ItemsSection> =
        when (val current = sections) {
            is LoadState.Loaded -> LoadState.Loaded(current.value.removing(id))
            else -> current
        }

    private companion object {
        const val TAG = "ListDetailStore"
        val UNDO_WINDOW = 5.seconds
    }
}

// ---- ItemsSection optimistic helpers (pure functions of the partitioned snapshot). ----

private fun ItemsSection.find(id: ItemId): Item? = active.firstOrNull { it.id == id } ?: completed.firstOrNull { it.id == id }

/** Move [id] between the active/completed partitions, flipping `isCompleted`, and recount. */
private fun ItemsSection.toggling(id: ItemId): ItemsSection {
    val target = find(id) ?: return this
    val flipped = target.copy(isCompleted = !target.isCompleted)
    val active = active.filterNot { it.id == id }
    val completed = completed.filterNot { it.id == id }
    return if (flipped.isCompleted) {
        copy(active = active, completed = completed + flipped, completedCount = completed.size + 1)
    } else {
        copy(active = active + flipped, completed = completed, completedCount = completed.size)
    }
}

/** Optimistically drop [id] from whichever partition holds it, and recount. */
private fun ItemsSection.removing(id: ItemId): ItemsSection {
    val active = active.filterNot { it.id == id }
    val completed = completed.filterNot { it.id == id }
    return ItemsSection(
        active = active,
        completed = completed,
        total = active.size + completed.size,
        completedCount = completed.size,
    )
}

/** [ItemsSection] → [LoadState]: a list with no items at all is [LoadState.Empty]. */
private fun ItemsSection.toLoadState(): LoadState<ItemsSection> = if (total == 0) LoadState.Empty else LoadState.Loaded(this)

// ---- ListDetailStore contract (§11: lives alongside its store). ----

public data class ListDetailState(
    val header: LoadState<ListDetail> = LoadState.Loading,
    val sections: LoadState<ItemsSection> = LoadState.Loading,
    val composerText: String = "",
    val composerError: String? = null,
    val showCompleted: Boolean = true,
    val pendingDelete: PendingItemDelete? = null,
)

/**
 * A soft-deleted item awaiting either undo or the end of its 5s window (§6).
 * Drives the snackbar countdown; the row is already removed from
 * [ListDetailState.sections] optimistically.
 */
public data class PendingItemDelete(
    val id: ItemId,
    val expiresAt: Instant,
)

public sealed interface ListDetailIntent {
    public data class Init(
        val listId: ListId,
    ) : ListDetailIntent

    public data object BackClicked : ListDetailIntent

    public data object MoreClicked : ListDetailIntent

    public data object ToggleShowCompleted : ListDetailIntent

    public data class ItemTapped(
        val id: ItemId,
    ) : ListDetailIntent

    public data class ItemCompletionToggled(
        val id: ItemId,
    ) : ListDetailIntent

    public data class ItemDeleteClicked(
        val id: ItemId,
    ) : ListDetailIntent

    public data object UndoItemDeleteClicked : ListDetailIntent

    /**
     * Self-dispatched 5s after a delete (§6). Carries the deleted [id] so a
     * re-delete that opened a fresh window isn't retired by a stale timer
     * (the Slice-4 `UndoWindowExpired(id)` pattern).
     */
    public data class UndoWindowExpired(
        val id: ItemId,
    ) : ListDetailIntent

    public data class ComposerTextChanged(
        val text: String,
    ) : ListDetailIntent

    public data object ComposerSubmit : ListDetailIntent

    public data object ClearCompletedClicked : ListDetailIntent
}

public sealed interface ListDetailEffect {
    public data object NavigateBack : ListDetailEffect

    public data class NavigateToEditItem(
        val id: ItemId,
    ) : ListDetailEffect

    public data object OpenListMenu : ListDetailEffect

    public data class ShowUndoSnackbar(
        val title: String,
        val secondsRemaining: Int,
    ) : ListDetailEffect

    public data class ShowError(
        val message: String,
    ) : ListDetailEffect
}
