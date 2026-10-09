package com.fluxit.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.DebugSeeder
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.toRepositoryApplicationError
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListRepository
import com.fluxit.domain.ScreenLoadState
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which Dashboard-owned mutation most recently failed, so the UI can offer a scoped
 * retry via [DashboardViewModel.retryFailedOperation] instead of a swallowed or uncaught
 * exception.
 */
enum class DashboardOperation { DELETE_LIST, RESTORE_LIST, SEED_SAMPLE_DATA }

/** Pairs the failed operation with the neutral, Firebase-free [ApplicationError]. */
data class DashboardOperationError(
    val operation: DashboardOperation,
    val error: ApplicationError,
)

data class DashboardUiState(
    /**
     * The raw (unfiltered) load state - see [ScreenLoadState]'s KDoc for the
     * loading/loaded/fatal-session distinctions and why cache/pending-writes live on
     * [ScreenLoadState.Loaded] rather than as separate sealed cases. [lists] below is the
     * pre-existing, search-filtered convenience view derived from this.
     */
    val loadState: ScreenLoadState<List<FluxListSummary>> = ScreenLoadState.Loading,
    val searchQuery: String = "",
    /**
     * List ids with a delete or restore currently in flight (including a retry). Lets
     * the UI disable that row's actions and doubles as this ViewModel's duplicate-submit guard -
     * see [DashboardViewModel.performDelete]/[DashboardViewModel.performRestore].
     */
    val pendingListIds: Set<String> = emptySet(),
    /** True while [DashboardViewModel.seedSampleData] (or its retry) is in flight. */
    val isSeeding: Boolean = false,
    /**
     * Non-null when the most recent delete/restore/seed attempt failed and has not
     * since been retried successfully or dismissed via [DashboardViewModel.dismissOperationError].
     */
    val operationError: DashboardOperationError? = null,
) {
    /**
     * The search-filtered list to render - empty while [loadState] carries no data
     * yet ([ScreenLoadState.Loading]/[ScreenLoadState.FatalSession]), the
     * [ScreenLoadState.Loaded] payload filtered by [searchQuery] otherwise. Kept as its own
     * field (rather than requiring every caller to match on [loadState] itself) so the
     * pre-existing `DashboardScreen` composable's `state.lists`/`state.isLoading` reads keep
     * compiling and behaving the same as before this task - [loadState] is additive.
     */
    val lists: List<FluxListSummary>
        get() {
            val all = (loadState as? ScreenLoadState.Loaded)?.data ?: return emptyList()
            return if (searchQuery.isBlank()) all
            else all.filter { it.list.name.contains(searchQuery.trim(), ignoreCase = true) }
        }

    /** True only before the very first [loadState] emission - derived so it can never
     * drift from [loadState] itself (previously a plain field the ViewModel set directly). */
    val isLoading: Boolean get() = loadState is ScreenLoadState.Loading

    /** True once the session backing this screen is known to be no longer valid - see
     * [ScreenLoadState.FatalSession]'s KDoc. */
    val isFatalSession: Boolean get() = loadState is ScreenLoadState.FatalSession

    /** True once [loadState] is [ScreenLoadState.Loaded] with zero rows - the
     * *unfiltered* server/cache state, independent of [searchQuery]. */
    val isEmpty: Boolean get() = (loadState as? ScreenLoadState.Loaded)?.data?.isEmpty() == true

    /** True while [loadState]'s data came from the local cache rather than a
     * confirmed server response (offline, or the very first frame before the network listener
     * attaches). See `RepositorySnapshot`'s KDoc for how much of the real signal is wired up. */
    val isFromCache: Boolean get() = (loadState as? ScreenLoadState.Loaded)?.isFromCache == true

    /** True while [loadState]'s data reflects at least one local write the server has
     * not yet acknowledged. */
    val hasPendingWrites: Boolean get() = (loadState as? ScreenLoadState.Loaded)?.hasPendingWrites == true
}

class DashboardViewModel(
    private val listRepository: ListRepository,
    /** Null where sample data is disabled (web); [seedSampleData] is then a no-op. */
    private val seeder: DebugSeeder?,
    /**
     * Combined with [listRepository]'s observation to derive
     * [ScreenLoadState.FatalSession] - reused verbatim from the session
     * machinery, not a parallel session-validity signal invented for this task. See
     * [ScreenLoadState]'s KDoc for why this is defense-in-depth rather than the primary
     * mechanism that reacts to a session becoming invalid (that is `SessionGate`'s job).
     */
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** Whether the dashboard offers the sample-data seeder at all. */
    val canSeedSampleData: Boolean = seeder != null

    private val searchQuery = MutableStateFlow("")

    private val pendingUndo = MutableStateFlow<String?>(null)
    val undoListId: StateFlow<String?> = pendingUndo.asStateFlow()
    private var undoJob: Job? = null

    private val pendingListIds = MutableStateFlow<Set<String>>(emptySet())
    private val isSeeding = MutableStateFlow(false)
    private val operationError = MutableStateFlow<DashboardOperationError?>(null)

    /**
     * The list id a failed delete/restore should retry against -
     * [DashboardOperationError] itself carries no payload, so the id is cached separately, the
     * same way `ItemDetailViewModel`'s `pendingReplaceBytes`/`pendingRemoveRef` do for photos.
     */
    private var pendingRetryListId: String? = null

    private val _deleteFailures = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Emits the id of a list whose delete just failed, so its swiped-away row can return to rest
     * (see `SwipeToDeleteContainer.resetSignal`). A one-shot event, not state: a repeated failure
     * for the same id must signal again.
     */
    val deleteFailures: SharedFlow<String> = _deleteFailures.asSharedFlow()

    /**
     * Merges the list observation with the auth session so a [ScreenLoadState] is
     * available to the outer `uiState` combine below without exceeding Kotlin's five-flow
     * direct-`combine`-overload ceiling (the outer combine already has five slots: this flow,
     * [searchQuery], [pendingListIds], [isSeeding], [operationError]).
     */
    private val listLoadState: Flow<ScreenLoadState<List<FluxListSummary>>> =
        combine(
            listRepository.observeListSummariesSnapshot(),
            authRepository.session,
        ) { snapshot, session ->
            if (session !is AuthSession.Authenticated) {
                ScreenLoadState.FatalSession
            } else {
                ScreenLoadState.Loaded(snapshot.value, snapshot.isFromCache, snapshot.hasPendingWrites)
            }
        }
            /**
             * `observeListSummariesSnapshot`'s `callbackFlow` calls
             * `close(exception)` on a terminal listener error (e.g. a real `PERMISSION_DENIED`
             * once the backing auth token is invalidated - sign-out, a revoked/expired token, or
             * a stale persisted session found invalid on cold launch). Per `callbackFlow`'s
             * contract that exception would otherwise rethrow uncaught through
             * [androidx.lifecycle.viewModelScope]'s `Dispatchers.Main.immediate` and crash the
             * app process instead of surfacing a recoverable state - the headline finding
             * (reproduced live 4x on iOS, once via a test-harness artifact on Android).
             * [kotlinx.coroutines.flow.Flow.catch] never intercepts `CancellationException` (it
             * is always rethrown unchanged, per its own contract), so ordinary
             * ViewModel-cleared/collector-cancelled cases are unaffected by this handler.
             *
             * **Judgment call, disclosed (not decided silently):** mapped to the existing
             * [ScreenLoadState.FatalSession] case rather than a new sealed case. This app's data
             * model scopes every collection strictly under `users/{uid}/...`
             * (`com.fluxit.data.remote.FirebaseSchema`) and the deployed Firestore Rules deny
             * access exactly when the request's auth uid stops matching that path's uid - so a
             * terminal listener error on this collection is, in this app's shape, always
             * functionally a session-validity problem, never a per-document access change a
             * still-genuinely-authenticated user could recover from by simply retrying the same
             * read. Reusing [ScreenLoadState.FatalSession] also keeps this the smallest complete
             * fix: no new UI state/copy is required, and it composes with the pre-existing
             * `authRepository.session`-driven fatal-session branch above with identical
             * semantics from the UI's perspective (empty, non-loading, defense-in-depth
             * rendering - see [ScreenLoadState]'s KDoc - until `SessionGate`'s own, separate
             * `authRepository.session` observation independently reacts and tears the screen
             * down). The one caveat, disclosed rather than silently accepted: if the backing
             * auth token is invalid enough to trip a Firestore listener but
             * `authRepository.session` itself has not (yet, or ever) independently reflected
             * that invalidity, this screen can remain in `FatalSession` with no further listener
             * re-attempt, relying on the user manually navigating away/back, backgrounding, or a
             * fresh app launch to recover - the same accepted bound this case already carried
             * before this task for its original (session-driven) trigger.
             */
            .catch { _ ->
                emit(ScreenLoadState.FatalSession)
            }

    val uiState: StateFlow<DashboardUiState> =
        combine(
            listLoadState,
            searchQuery,
            pendingListIds,
            isSeeding,
            operationError,
        ) { loadState, query, pendingIds, seeding, error ->
            DashboardUiState(
                loadState = loadState,
                searchQuery = query,
                pendingListIds = pendingIds,
                isSeeding = seeding,
                operationError = error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    // The scheduled backend owns expired tombstone cleanup. Dashboard startup
    // must not purge locally; soft-delete and the five-second undo remain below.
    fun onSearchChange(query: String) {
        searchQuery.value = query
    }

    fun deleteList(listId: String) {
        undoJob?.cancel()
        performDelete(listId)
    }

    fun undoDelete() {
        val id = pendingUndo.value ?: return
        undoJob?.cancel()
        pendingUndo.value = null
        performRestore(id)
    }

    fun dismissUndo() {
        pendingUndo.value = null
    }

    /**
     * A second call while a seed is already in flight (a double-tap, or a retry racing
     * a fresh tap) is a no-op. On failure, [DashboardUiState.isSeeding] is still reset
     * (`finally`) and a retryable [DashboardUiState.operationError] is surfaced instead of an
     * uncaught exception from `viewModelScope.launch`.
     */
    fun seedSampleData() {
        val seeder = seeder ?: return
        if (isSeeding.value) return
        isSeeding.value = true
        clearErrorFor(DashboardOperation.SEED_SAMPLE_DATA)
        viewModelScope.launch {
            try {
                seeder.seed()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                operationError.value = DashboardOperationError(
                    DashboardOperation.SEED_SAMPLE_DATA,
                    failure.toRepositoryApplicationError(),
                )
            } finally {
                isSeeding.value = false
            }
        }
    }

    /**
     * Re-attempts whichever operation last failed, using [pendingRetryListId] for
     * delete/restore. A no-op if nothing failed, or if the same list id (or a seed) already has
     * a matching operation in flight - [performDelete]/[performRestore]/[seedSampleData] each
     * re-check their own guard.
     */
    fun retryFailedOperation() {
        val failed = operationError.value ?: return
        when (failed.operation) {
            DashboardOperation.DELETE_LIST -> pendingRetryListId?.let { performDelete(it) }
            DashboardOperation.RESTORE_LIST -> pendingRetryListId?.let { performRestore(it) }
            DashboardOperation.SEED_SAMPLE_DATA -> seedSampleData()
        }
    }

    /** Clears a shown [DashboardUiState.operationError] without retrying. */
    fun dismissOperationError() {
        operationError.value = null
    }

    /**
     * Soft-deletes [listId] and, on success, starts the five-second undo window -
     * exactly the pre-existing behavior. A second call for the *same* [listId] while the first
     * is still in flight is a no-op, per [pendingListIds] (checked and updated synchronously,
     * before the coroutine is even launched, so a double-tap can never race past the guard). On
     * failure, [pendingListIds] is still reset (`finally`) and a retryable
     * [DashboardOperationError] is surfaced; [pendingUndo] is only ever set on success, so a
     * failed delete never shows a phantom undo affordance.
     */
    private fun performDelete(listId: String) {
        if (listId in pendingListIds.value) return
        pendingListIds.update { it + listId }
        clearErrorFor(DashboardOperation.DELETE_LIST)
        viewModelScope.launch {
            try {
                listRepository.softDeleteList(listId)
                pendingUndo.value = listId
                undoJob = viewModelScope.launch {
                    delay(5_000)
                    pendingUndo.value = null
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                pendingRetryListId = listId
                operationError.value =
                    DashboardOperationError(DashboardOperation.DELETE_LIST, failure.toRepositoryApplicationError())
                _deleteFailures.tryEmit(listId)
            } finally {
                pendingListIds.update { it - listId }
            }
        }
    }

    /** Mirrors [performDelete]'s try/finally and duplicate-guard shape for restore. */
    private fun performRestore(listId: String) {
        if (listId in pendingListIds.value) return
        pendingListIds.update { it + listId }
        clearErrorFor(DashboardOperation.RESTORE_LIST)
        viewModelScope.launch {
            try {
                listRepository.restoreList(listId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                pendingRetryListId = listId
                operationError.value =
                    DashboardOperationError(DashboardOperation.RESTORE_LIST, failure.toRepositoryApplicationError())
            } finally {
                pendingListIds.update { it - listId }
            }
        }
    }

    private fun clearErrorFor(operation: DashboardOperation) {
        if (operationError.value?.operation == operation) operationError.value = null
    }
}
