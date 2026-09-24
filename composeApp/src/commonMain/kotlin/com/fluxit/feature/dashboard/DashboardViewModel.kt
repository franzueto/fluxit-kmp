package com.fluxit.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.DebugSeeder
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.SessionTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val lists: List<FluxListSummary> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
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
)

class DashboardViewModel(
    private val listRepository: ListRepository,
    private val seeder: DebugSeeder,
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

    val uiState: StateFlow<DashboardUiState> =
        combine(
            listRepository.observeListSummaries()
                // FB-104 evidence hook: this is the first user-scoped data listener the
                // app starts (Room today, Firestore from Phase 2). Tracing it lets the
                // manual matrix show, from an ordinary log capture, that it never starts
                // before the session gate has resolved.
                .onStart { SessionTrace.event("user-scoped list listener STARTED") },
            searchQuery,
            pendingListIds,
            isSeeding,
            operationError,
        ) { lists, query, pendingIds, seeding, error ->
            DashboardUiState(
                lists = if (query.isBlank()) lists
                else lists.filter { it.list.name.contains(query.trim(), ignoreCase = true) },
                searchQuery = query,
                isLoading = false,
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
