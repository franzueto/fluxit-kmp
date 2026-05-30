package dev.franzueto.fluxit.shared.state.store

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.model.ListSummary
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.Clock
import dev.franzueto.fluxit.shared.domain.usecase.lists.DeleteList
import dev.franzueto.fluxit.shared.domain.usecase.lists.ObserveLists
import dev.franzueto.fluxit.shared.domain.usecase.lists.SearchLists
import dev.franzueto.fluxit.shared.state.error.userMessage
import dev.franzueto.fluxit.shared.state.navigation.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Dashboard store backing the Lists tab (Phase 07; `plan/05` §4/§6/§7, ADR-014).
 *
 * Composes three list use cases:
 * - **Feed / search.** An internal query [MutableStateFlow] is debounced 200ms,
 *   deduplicated, and `flatMapLatest`-ed into [SearchLists] (non-blank) or
 *   [ObserveLists] (blank → full feed) — see [search wiring][startFeed]. A blank
 *   query skips the debounce (selector returns [Duration.ZERO]) so the first
 *   load and "clear search" are immediate.
 * - **Optimistic delete + undo (§6).** [ListsIntent.DeleteListClicked] removes
 *   the row from state immediately, calls [DeleteList], and on success opens a 5s
 *   undo window ([ListsState.pendingDelete] + a timer that self-dispatches
 *   [ListsIntent.UndoWindowExpired]). A second delete while a window is open
 *   finalizes the first immediately, then opens a fresh window.
 *
 * **Undo restore is data-layer-blocked.** There is no `UndoDeleteList` use case —
 * the shipped `ListsRepository` exposes no `deleted_at = NULL` restore primitive
 * (see `DeleteList`'s KDoc). [ListsIntent.UndoDeleteClicked] therefore only
 * dismisses the snackbar (cancel timer + clear `pendingDelete`); the soft-delete
 * stands. A real restore is a documented TODO tied to that data-layer deferral.
 *
 * Navigation is expressed as one-shot [effects][ListsEffect] (§14 default), never
 * observed state. Per ADR-004, Calendar/Starred are not built — the host shows a
 * "Coming soon" placeholder when it receives [ListsEffect.NavigateToTab] for them.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
public class ListsDashboardStore(
    private val scope: CoroutineScope,
    logger: AppLogger,
    private val observeLists: ObserveLists,
    private val searchLists: SearchLists,
    private val deleteList: DeleteList,
    private val clock: Clock,
) : BaseStore<ListsState, ListsIntent, ListsEffect>(ListsState(), scope, logger) {
    /** Latest raw query — updated synchronously on keystroke so the field stays responsive (§7). */
    private val queryFlow = MutableStateFlow("")

    /** Bumped by [ListsIntent.Refresh] to force the reactive feed to re-subscribe. */
    private val refreshTick = MutableStateFlow(0)

    /** The in-flight undo-window timer, if any; cancelled on undo / re-delete / expiry. */
    private var undoTimer: Job? = null

    init {
        startFeed()
    }

    override suspend fun reduce(intent: ListsIntent) {
        when (intent) {
            ListsIntent.Refresh -> {
                update { copy(lists = LoadState.Loading) }
                refreshTick.value += 1
            }
            is ListsIntent.SearchQueryChanged -> {
                queryFlow.value = intent.query
                update { copy(searchQuery = intent.query) }
            }
            is ListsIntent.OpenList -> emit(ListsEffect.NavigateToListDetail(intent.id))
            ListsIntent.CreateListClicked -> emit(ListsEffect.NavigateToCreateList)
            is ListsIntent.DeleteListClicked -> delete(intent.id)
            ListsIntent.UndoDeleteClicked -> undoDelete()
            is ListsIntent.UndoWindowExpired -> expireUndoWindow(intent.id)
            is ListsIntent.TabSelected -> emit(ListsEffect.NavigateToTab(intent.tab))
        }
    }

    /**
     * Wire the search/feed pipeline. Keyed on (query, refreshTick): a blank query
     * has a zero debounce (immediate feed), a real query debounces 200ms;
     * `distinctUntilChanged` over the [FeedKey] swallows no-op keystrokes while
     * still letting a [ListsIntent.Refresh] (tick bump) through. Read errors have
     * no `Outcome` channel on these use cases, so a thrown failure maps to
     * [LoadState.Error] defensively.
     */
    private fun startFeed() {
        scope.launch {
            combine(queryFlow, refreshTick) { query, tick -> FeedKey(query, tick) }
                .debounce { key -> if (key.query.isBlank()) Duration.ZERO else SEARCH_DEBOUNCE }
                .distinctUntilChanged()
                .flatMapLatest { key ->
                    if (key.query.isBlank()) observeLists() else searchLists(key.query)
                }.catch { cause ->
                    logger.warn(TAG, "lists feed failed: ${cause.message}")
                    update { copy(lists = LoadState.Error("Something went wrong loading your lists.")) }
                }.collect { summaries ->
                    update { copy(lists = summaries.toLoadState()) }
                }
        }
    }

    private suspend fun delete(id: ListId) {
        // Rapid second delete: finalize the still-open window first (the prior
        // soft-delete already persisted; "finalize" just stops offering its undo),
        // then open a fresh window for this delete.
        if (currentState.pendingDelete != null) finalizePendingDelete()

        val snapshot = (currentState.lists as? LoadState.Loaded)?.value
        val result =
            optimistic(
                apply = { copy(lists = removeFromLists(id)) },
                revert = { copy(lists = snapshot?.toLoadState() ?: lists) },
                op = { deleteList(id) },
                onError = { emit(ListsEffect.ShowError(it.userMessage)) },
            )
        if (result is Outcome.Ok) {
            val deleted = result.value
            val expiresAt = clock.now().plus(UNDO_WINDOW)
            update { copy(pendingDelete = PendingDelete(deleted.id, expiresAt)) }
            emit(ListsEffect.ShowUndoSnackbar(deleted.name, UNDO_WINDOW.inWholeSeconds.toInt()))
            startUndoTimer(deleted.id)
        }
    }

    private fun startUndoTimer(id: ListId) {
        undoTimer?.cancel()
        undoTimer =
            scope.launch {
                delay(UNDO_WINDOW)
                dispatch(ListsIntent.UndoWindowExpired(id))
            }
    }

    private fun expireUndoWindow(id: ListId) {
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
        // TODO(data-layer): call UndoDeleteList(pending.id) once a `deleted_at = NULL`
        // restore primitive lands (blocked today — see DeleteList KDoc). The deleted
        // list's cancelledReminderIds are preserved on DeletedListSummary for the
        // future reschedule. Until then the soft-delete stands and a live feed
        // emission would clobber any optimistic local re-insert, so we only dismiss
        // the snackbar rather than fake a restore.
        logger.warn(TAG, "undo requested for ${pending.id.value} but UndoDeleteList is not yet available; delete stands")
    }

    /** Cancel the undo timer and clear [ListsState.pendingDelete]. */
    private fun finalizePendingDelete() {
        undoTimer?.cancel()
        undoTimer = null
        update { copy(pendingDelete = null) }
    }

    private fun ListsState.removeFromLists(id: ListId): LoadState<List<ListSummary>> =
        when (val current = lists) {
            is LoadState.Loaded -> current.value.filterNot { it.id == id }.toLoadState()
            else -> current
        }

    private companion object {
        const val TAG = "ListsDashboardStore"
        val SEARCH_DEBOUNCE = 200.milliseconds
        val UNDO_WINDOW = 5.seconds
    }

    /** (query, refreshTick) pipeline key — see [startFeed]. */
    private data class FeedKey(
        val query: String,
        val tick: Int,
    )
}

// ---- ListsDashboardStore contract (§11: lives alongside its store). ----

public data class ListsState(
    val searchQuery: String = "",
    val lists: LoadState<List<ListSummary>> = LoadState.Loading,
    val pendingDelete: PendingDelete? = null,
)

/**
 * A soft-deleted list awaiting either undo or the end of its 5s window (§6).
 * Drives the snackbar countdown; the row is already removed from
 * [ListsState.lists] optimistically.
 */
public data class PendingDelete(
    val id: ListId,
    val expiresAt: Instant,
)

public sealed interface ListsIntent {
    /** Force the reactive feed to re-subscribe (pull-to-refresh). */
    public data object Refresh : ListsIntent

    public data class SearchQueryChanged(
        val query: String,
    ) : ListsIntent

    public data class OpenList(
        val id: ListId,
    ) : ListsIntent

    public data object CreateListClicked : ListsIntent

    public data class DeleteListClicked(
        val id: ListId,
    ) : ListsIntent

    public data object UndoDeleteClicked : ListsIntent

    /**
     * Self-dispatched 5s after a delete (§6). Carries the deleted [id] so a
     * re-delete that opened a fresh window isn't retired by a stale timer.
     * (Diverges from the §4 sketch's no-arg `UndoWindowExpired` to make that
     * match precise.)
     */
    public data class UndoWindowExpired(
        val id: ListId,
    ) : ListsIntent

    public data class TabSelected(
        val tab: Tab,
    ) : ListsIntent
}

public sealed interface ListsEffect {
    public data class NavigateToListDetail(
        val id: ListId,
    ) : ListsEffect

    public data object NavigateToCreateList : ListsEffect

    public data class ShowUndoSnackbar(
        val name: String,
        val secondsRemaining: Int,
    ) : ListsEffect

    public data class ShowError(
        val message: String,
    ) : ListsEffect

    /** Calendar/Starred route to a "Coming soon" placeholder in the host (ADR-004). */
    public data class NavigateToTab(
        val tab: Tab,
    ) : ListsEffect
}
