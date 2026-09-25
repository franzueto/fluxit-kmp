package com.fluxit.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.DebugSeeder
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListRepository
import com.fluxit.domain.ScreenLoadState
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.SessionTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `FB-402`: which Dashboard-owned mutation most recently failed, so the UI can offer a scoped
 * retry via [DashboardViewModel.retryFailedOperation] instead of a swallowed or uncaught
 * exception.
 */
enum class DashboardOperation { DELETE_LIST, RESTORE_LIST, SEED_SAMPLE_DATA }

/** `FB-402`: pairs the failed operation with FB-401's neutral, Firebase-free [ApplicationError]. */
data class DashboardOperationError(
    val operation: DashboardOperation,
    val error: ApplicationError,
)

data class DashboardUiState(
    /**
     * `FB-404`: the raw (unfiltered) load state - see [ScreenLoadState]'s KDoc for the
     * loading/loaded/fatal-session distinctions and why cache/pending-writes live on
     * [ScreenLoadState.Loaded] rather than as separate sealed cases. [lists] below is the
     * pre-existing, search-filtered convenience view derived from this.
     */
    val loadState: ScreenLoadState<List<FluxListSummary>> = ScreenLoadState.Loading,
    val searchQuery: String = "",
    /**
     * `FB-402`: list ids with a delete or restore currently in flight (including a retry). Lets
     * the UI disable that row's actions and doubles as this ViewModel's duplicate-submit guard -
     * see [DashboardViewModel.performDelete]/[DashboardViewModel.performRestore].
     */
    val pendingListIds: Set<String> = emptySet(),
    /** `FB-402`: true while [DashboardViewModel.seedSampleData] (or its retry) is in flight. */
    val isSeeding: Boolean = false,
    /**
     * `FB-402`: non-null when the most recent delete/restore/seed attempt failed and has not
     * since been retried successfully or dismissed via [DashboardViewModel.dismissOperationError].
     */
    val operationError: DashboardOperationError? = null,
) {
    /**
     * `FB-404`: the search-filtered list to render - empty while [loadState] carries no data
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

    /** `FB-404`: true only before the very first [loadState] emission - derived so it can never
     * drift from [loadState] itself (previously a plain field the ViewModel set directly). */
    val isLoading: Boolean get() = loadState is ScreenLoadState.Loading

    /** `FB-404`: true once the session backing this screen is known to be no longer valid - see
     * [ScreenLoadState.FatalSession]'s KDoc. */
    val isFatalSession: Boolean get() = loadState is ScreenLoadState.FatalSession

    /** `FB-404`: true once [loadState] is [ScreenLoadState.Loaded] with zero rows - the
     * *unfiltered* server/cache state, independent of [searchQuery]. */
    val isEmpty: Boolean get() = (loadState as? ScreenLoadState.Loaded)?.data?.isEmpty() == true

    /** `FB-404`: true while [loadState]'s data came from the local cache rather than a
     * confirmed server response (offline, or the very first frame before the network listener
     * attaches). See `RepositorySnapshot`'s KDoc for how much of the real signal is wired up. */
    val isFromCache: Boolean get() = (loadState as? ScreenLoadState.Loaded)?.isFromCache == true

    /** `FB-404`: true while [loadState]'s data reflects at least one local write the server has
     * not yet acknowledged. */
    val hasPendingWrites: Boolean get() = (loadState as? ScreenLoadState.Loaded)?.hasPendingWrites == true
}

class DashboardViewModel(
    private val listRepository: ListRepository,
    private val seeder: DebugSeeder,
    /**
     * `FB-404`: combined with [listRepository]'s observation to derive
     * [ScreenLoadState.FatalSession] - reused verbatim from `FB-101`/`FB-105`'s session
     * machinery, not a parallel session-validity signal invented for this task. See
     * [ScreenLoadState]'s KDoc for why this is defense-in-depth rather than the primary
     * mechanism that reacts to a session becoming invalid (that is `SessionGate`'s job).
     */
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    private val pendingUndo = MutableStateFlow<String?>(null)
    val undoListId: StateFlow<String?> = pendingUndo.asStateFlow()
    private var undoJob: Job? = null

    private val pendingListIds = MutableStateFlow<Set<String>>(emptySet())
    private val isSeeding = MutableStateFlow(false)
    private val operationError = MutableStateFlow<DashboardOperationError?>(null)

    /**
     * `FB-402`: the list id a failed delete/restore should retry against -
     * [DashboardOperationError] itself carries no payload, so the id is cached separately, the
     * same shape `ItemDetailViewModel`'s `pendingReplaceBytes`/`pendingRemoveRef` use for FB-306.
     */
    private var pendingRetryListId: String? = null

    /**
     * `FB-404`: merges the list observation with the auth session so a [ScreenLoadState] is
     * available to the outer `uiState` combine below without exceeding Kotlin's five-flow
     * direct-`combine`-overload ceiling (the outer combine already has five slots: this flow,
     * [searchQuery], [pendingListIds], [isSeeding], [operationError]).
     */
    private val listLoadState: Flow<ScreenLoadState<List<FluxListSummary>>> =
        combine(
            listRepository.observeListSummariesSnapshot()
                // FB-104 evidence hook: this is the first user-scoped data listener the
                // app starts (Room today, Firestore from Phase 2). Tracing it lets the
                // manual matrix show, from an ordinary log capture, that it never starts
                // before the session gate has resolved.
                .onStart { SessionTrace.event("user-scoped list listener STARTED") },
            authRepository.session,
        ) { snapshot, session ->
            if (session !is AuthSession.Authenticated) {
                ScreenLoadState.FatalSession
            } else {
                ScreenLoadState.Loaded(snapshot.value, snapshot.isFromCache, snapshot.hasPendingWrites)
            }
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

    init {
        SessionTrace.event("DashboardViewModel created (user-scoped consumer)")
        viewModelScope.launch { listRepository.purgeExpired() }
    }

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
     * `FB-402`: a second call while a seed is already in flight (a double-tap, or a retry racing
     * a fresh tap) is a no-op. On failure, [DashboardUiState.isSeeding] is still reset
     * (`finally`) and a retryable [DashboardUiState.operationError] is surfaced instead of an
     * uncaught exception from `viewModelScope.launch`.
     */
    fun seedSampleData() {
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
                    failure.toDashboardApplicationError(),
                )
            } finally {
                isSeeding.value = false
            }
        }
    }

    /**
     * `FB-402`: re-attempts whichever operation last failed, using [pendingRetryListId] for
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
     * `FB-402`: soft-deletes [listId] and, on success, starts the five-second undo window -
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
                    DashboardOperationError(DashboardOperation.DELETE_LIST, failure.toDashboardApplicationError())
            } finally {
                pendingListIds.update { it - listId }
            }
        }
    }

    /** `FB-402`: mirrors [performDelete]'s try/finally and duplicate-guard shape for restore. */
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
                    DashboardOperationError(DashboardOperation.RESTORE_LIST, failure.toDashboardApplicationError())
            } finally {
                pendingListIds.update { it - listId }
            }
        }
    }

    private fun clearErrorFor(operation: DashboardOperation) {
        if (operationError.value?.operation == operation) operationError.value = null
    }
}

/**
 * `FB-402`: [ListRepository] failures carry no `commonMain`-visible error code today. Each
 * platform's Firestore adapter maps its SDK exception to FB-401's neutral [ApplicationError]
 * internally (see the `internal class ListRepositoryException` in the `androidMain`/`iosMain`
 * `firebase/list` packages), but that type is `internal` to its own platform source set and
 * structurally cannot be referenced from this shared `commonMain` ViewModel - a `commonMain`
 * file compiles against every target, including ones where the class does not exist at all.
 * Until that boundary is widened to a `commonMain`-visible type - the same gap `FB-401-NB1`/
 * `FB-401-NB2` already tracked for `PhotoStorage`, owned by `FB-403` - any failure caught here
 * is conservatively reported as [RepositoryErrorCode.UNKNOWN] (`canRetry = true`) rather than a
 * more specific code this layer cannot actually know.
 */
private fun Throwable.toDashboardApplicationError(): ApplicationError =
    RepositoryErrorCode.UNKNOWN.toApplicationError()
