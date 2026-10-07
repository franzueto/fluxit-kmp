package com.fluxit.feature.listdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.toRepositoryApplicationError
import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.ItemRepository
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
 * Which item-level mutation most recently failed, so the UI can offer a scoped retry
 * via [ListDetailViewModel.retryFailedOperation] instead of a swallowed or uncaught exception -
 * exact counterpart of `DashboardOperation`, scoped to this screen's operations.
 */
enum class ListDetailOperation { ADD_ITEM, TOGGLE_COMPLETED, DELETE_ITEM, RESTORE_ITEM, CLEAR_COMPLETED, DELETE_LIST }

/** Pairs the failed operation with the neutral, Firebase-free [ApplicationError]. */
data class ListDetailOperationError(
    val operation: ListDetailOperation,
    val error: ApplicationError,
)

data class ListDetailUiState(
    val list: FluxList? = null,
    /**
     * The raw items load state - see [ScreenLoadState]'s KDoc for the
     * loading/loaded/fatal-session distinctions and why cache/pending-writes live on
     * [ScreenLoadState.Loaded] rather than as separate sealed cases.
     * [activeItems]/[completedItems] below are the pre-existing, filtered convenience views
     * derived from this.
     */
    val itemsLoadState: ScreenLoadState<List<FluxItem>> = ScreenLoadState.Loading,
    val showCompleted: Boolean = true,
    val composerText: String = "",
    val listDeleted: Boolean = false,
) {
    /** Derived from [itemsLoadState] - empty while it is not yet
     * [ScreenLoadState.Loaded] (kept as its own field, rather than requiring every caller to
     * match on [itemsLoadState] itself, so pre-existing reads keep compiling unchanged). */
    val activeItems: List<FluxItem>
        get() = (itemsLoadState as? ScreenLoadState.Loaded)?.data?.filter { !it.isCompleted } ?: emptyList()

    /** See [activeItems]'s KDoc. */
    val completedItems: List<FluxItem>
        get() = (itemsLoadState as? ScreenLoadState.Loaded)?.data?.filter { it.isCompleted } ?: emptyList()

    val totalCount: Int get() = activeItems.size + completedItems.size
    val completedCount: Int get() = completedItems.size

    /** True only before the very first [itemsLoadState] emission. */
    val isLoading: Boolean get() = itemsLoadState is ScreenLoadState.Loading

    /** True once the session backing this screen is known to be no longer valid - see
     * [ScreenLoadState.FatalSession]'s KDoc. */
    val isFatalSession: Boolean get() = itemsLoadState is ScreenLoadState.FatalSession

    /** True once [itemsLoadState] is [ScreenLoadState.Loaded] with zero items. */
    val isEmpty: Boolean get() = (itemsLoadState as? ScreenLoadState.Loaded)?.data?.isEmpty() == true

    /** True while [itemsLoadState]'s data came from the local cache rather than a
     * confirmed server response. See `RepositorySnapshot`'s KDoc for how much of the real
     * signal is wired up. */
    val isFromCache: Boolean get() = (itemsLoadState as? ScreenLoadState.Loaded)?.isFromCache == true

    /** True while [itemsLoadState]'s data reflects at least one local write the server
     * has not yet acknowledged. */
    val hasPendingWrites: Boolean get() = (itemsLoadState as? ScreenLoadState.Loaded)?.hasPendingWrites == true
}

class ListDetailViewModel(
    private val listId: String,
    private val listRepository: ListRepository,
    private val itemRepository: ItemRepository,
    /**
     * Combined with [itemRepository]'s observation to derive
     * [ScreenLoadState.FatalSession] - see `DashboardViewModel`'s identically-purposed
     * constructor param KDoc for why this reuses the session machinery rather
     * than inventing a parallel signal.
     */
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val showCompleted = MutableStateFlow(true)
    private val composerText = MutableStateFlow("")
    private val listDeleted = MutableStateFlow(false)

    private val pendingUndo = MutableStateFlow<String?>(null)
    val undoItemId: StateFlow<String?> = pendingUndo.asStateFlow()
    private var undoJob: Job? = null

    /**
     * Item ids with a toggle/delete/restore currently in flight (including a retry).
     * Lets the UI disable that row's actions and doubles as this ViewModel's duplicate-submit
     * guard - see [performToggle]/[performDelete]/[performRestore]. Exposed as its own
     * [StateFlow], the same shape [undoItemId] already uses in this file, rather than folded
     * into [ListDetailUiState] - that data class's backing `combine` is already at Kotlin's
     * 5-flow direct-overload ceiling.
     */
    private val _pendingItemIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingItemIds: StateFlow<Set<String>> = _pendingItemIds.asStateFlow()

    /** True while [submitComposer]'s `addItem` (or its retry) is in flight. */
    private val _isAddingItem = MutableStateFlow(false)
    val isAddingItem: StateFlow<Boolean> = _isAddingItem.asStateFlow()

    /** True while [clearCompleted] (or its retry) is in flight. */
    private val _isClearingCompleted = MutableStateFlow(false)
    val isClearingCompleted: StateFlow<Boolean> = _isClearingCompleted.asStateFlow()

    /** True while [deleteList] (or its retry) is in flight - same duplicate-submit
     * guard shape as [_isAddingItem]/[_isClearingCompleted], kept as its own [StateFlow] for the
     * same "already at the outer `combine`'s 5-flow ceiling" reason those are. */
    private val _isDeletingList = MutableStateFlow(false)
    val isDeletingList: StateFlow<Boolean> = _isDeletingList.asStateFlow()

    /**
     * Non-null when the most recent add/toggle/delete/restore/clear-completed attempt
     * failed and has not since been retried successfully or dismissed via [dismissOperationError].
     */
    private val _operationError = MutableStateFlow<ListDetailOperationError?>(null)
    val operationError: StateFlow<ListDetailOperationError?> = _operationError.asStateFlow()

    /** Inputs a failed operation needs to retry - only ever one operation's worth is
     * live at a time, since [_operationError] itself is a single slot. */
    private var pendingRetryAddTitle: String? = null
    private var pendingRetryItemId: String? = null

    private val _itemDeleteFailures = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits the id of an item whose delete just failed; see `DashboardViewModel.deleteFailures`. */
    val itemDeleteFailures: SharedFlow<String> = _itemDeleteFailures.asSharedFlow()
    private var pendingRetryToggleTarget: Boolean? = null

    /**
     * Merges the item observation with the auth session so a [ScreenLoadState] is
     * available to the outer `uiState` combine below without exceeding Kotlin's five-flow
     * direct-`combine`-overload ceiling (the outer combine already has five slots:
     * [listRepository]'s `observeList`, this flow, [showCompleted], [composerText],
     * [listDeleted]).
     */
    private val itemsLoadState: Flow<ScreenLoadState<List<FluxItem>>> = combine(
        itemRepository.observeItemsSnapshot(listId),
        authRepository.session,
    ) { snapshot, session ->
        if (session !is AuthSession.Authenticated) {
            ScreenLoadState.FatalSession
        } else {
            ScreenLoadState.Loaded(snapshot.value, snapshot.isFromCache, snapshot.hasPendingWrites)
        }
    }
        /**
         * Same guard as `DashboardViewModel.listLoadState`'s `.catch` - see that KDoc for the
         * full rationale, including why [ScreenLoadState.FatalSession] is reused rather than a new
         * sealed case. `itemRepository.observeItemsSnapshot(listId)`'s `callbackFlow` calls
         * `close(exception)` on a terminal listener error the same way
         * `observeListSummariesSnapshot` does.
         */
        .catch { _ -> emit(ScreenLoadState.FatalSession) }

    /**
     * `listRepository.observeList`'s
     * `callbackFlow` calls `close(exception)` on a terminal listener error the exact same way
     * `itemRepository.observeItemsSnapshot`/`observeListSummariesSnapshot` do, so this flow needs
     * its own `.catch` as well: without it, a real terminal error on the *list document*
     * listener would rethrow uncaught through `viewModelScope`'s `stateIn` and
     * crash the app process, even though [itemsLoadState] already guards the items listener.
     * Mapped to `null` (not a new [ScreenLoadState]) since this flow only ever feeds
     * [ListDetailUiState.list] - a single optional value with no loading/cache semantics of its
     * own; [itemsLoadState] and the direct `authRepository.session` check
     * inside it remain the authoritative source for [ListDetailUiState.isFatalSession], so a
     * `null` list here composes correctly with the pre-existing "list not found/not yet loaded"
     * rendering without inventing new UI state.
     */
    private val list: Flow<FluxList?> = listRepository.observeList(listId).catch { _ -> emit(null) }

    val uiState: StateFlow<ListDetailUiState> = combine(
        list,
        itemsLoadState,
        showCompleted,
        composerText,
        listDeleted,
    ) { list, itemsState, show, text, deleted ->
        ListDetailUiState(
            list = list,
            itemsLoadState = itemsState,
            showCompleted = show,
            composerText = text,
            listDeleted = deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDetailUiState())

    fun onComposerChange(text: String) {
        composerText.value = text
    }

    /**
     * A second call while an add is already in flight is a no-op -
     * [_isAddingItem] is set synchronously, before the coroutine is even launched. The composer
     * text is still cleared immediately on submit (unchanged, pre-existing UX), so the
     * submitted title is separately cached in [pendingRetryAddTitle] for [retryFailedOperation]
     * to redrive without the user retyping it.
     */
    fun submitComposer() {
        val title = composerText.value.trim()
        if (title.isEmpty() || _isAddingItem.value) return
        composerText.value = ""
        performAddItem(title)
    }

    private fun performAddItem(title: String) {
        _isAddingItem.value = true
        clearErrorFor(ListDetailOperation.ADD_ITEM)
        viewModelScope.launch {
            try {
                itemRepository.addItem(listId, title)
                pendingRetryAddTitle = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                pendingRetryAddTitle = title
                _operationError.value =
                    ListDetailOperationError(ListDetailOperation.ADD_ITEM, failure.toRepositoryApplicationError())
            } finally {
                _isAddingItem.value = false
            }
        }
    }

    fun toggleCompleted(item: FluxItem) {
        performToggle(item.id, !item.isCompleted)
    }

    /** Mirrors [performDelete]'s try/finally and duplicate-guard shape. */
    private fun performToggle(itemId: String, target: Boolean) {
        if (itemId in _pendingItemIds.value) return
        _pendingItemIds.update { it + itemId }
        clearErrorFor(ListDetailOperation.TOGGLE_COMPLETED)
        viewModelScope.launch {
            try {
                itemRepository.setCompleted(listId, itemId, target)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                pendingRetryItemId = itemId
                pendingRetryToggleTarget = target
                _operationError.value =
                    ListDetailOperationError(ListDetailOperation.TOGGLE_COMPLETED, failure.toRepositoryApplicationError())
            } finally {
                _pendingItemIds.update { it - itemId }
            }
        }
    }

    fun toggleShowCompleted() {
        showCompleted.value = !showCompleted.value
    }

    fun deleteItem(itemId: String) {
        undoJob?.cancel()
        performDelete(itemId)
    }

    /**
     * Soft-deletes [itemId] and, on success, starts the five-second undo window. A
     * second call for the *same* [itemId] while the first is still in flight is a no-op, per
     * [_pendingItemIds] (checked and updated synchronously, before the coroutine is even
     * launched). On failure, [_pendingItemIds] is still reset (`finally`) and a retryable
     * [ListDetailOperationError] is surfaced; [pendingUndo] is only ever set on success (a
     * correctness fix over the prior optimistic-before-the-call assignment - the same fix
     * `DashboardViewModel.performDelete`,, already made for lists), so a failed delete
     * never shows a phantom undo affordance.
     */
    private fun performDelete(itemId: String) {
        if (itemId in _pendingItemIds.value) return
        _pendingItemIds.update { it + itemId }
        clearErrorFor(ListDetailOperation.DELETE_ITEM)
        viewModelScope.launch {
            try {
                itemRepository.softDeleteItem(listId, itemId)
                pendingUndo.value = itemId
                undoJob = viewModelScope.launch {
                    delay(5_000)
                    pendingUndo.value = null
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                pendingRetryItemId = itemId
                _operationError.value =
                    ListDetailOperationError(ListDetailOperation.DELETE_ITEM, failure.toRepositoryApplicationError())
                _itemDeleteFailures.tryEmit(itemId)
            } finally {
                _pendingItemIds.update { it - itemId }
            }
        }
    }

    fun undoDelete() {
        val id = pendingUndo.value ?: return
        undoJob?.cancel()
        pendingUndo.value = null
        performRestore(id)
    }

    /** Mirrors [performDelete]'s try/finally and duplicate-guard shape for restore. */
    private fun performRestore(itemId: String) {
        if (itemId in _pendingItemIds.value) return
        _pendingItemIds.update { it + itemId }
        clearErrorFor(ListDetailOperation.RESTORE_ITEM)
        viewModelScope.launch {
            try {
                itemRepository.restoreItem(listId, itemId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                pendingRetryItemId = itemId
                _operationError.value =
                    ListDetailOperationError(ListDetailOperation.RESTORE_ITEM, failure.toRepositoryApplicationError())
            } finally {
                _pendingItemIds.update { it - itemId }
            }
        }
    }

    fun dismissUndo() {
        pendingUndo.value = null
    }

    /**
     * A second call while a clear is already in flight is a no-op -
     * [_isClearingCompleted] is set synchronously, before the coroutine is even launched. On
     * failure, the flag is still reset (`finally`) and a retryable [ListDetailOperationError]
     * is surfaced instead of an uncaught exception from `viewModelScope.launch`.
     */
    fun clearCompleted() {
        if (_isClearingCompleted.value) return
        _isClearingCompleted.value = true
        clearErrorFor(ListDetailOperation.CLEAR_COMPLETED)
        viewModelScope.launch {
            try {
                itemRepository.clearCompleted(listId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _operationError.value =
                    ListDetailOperationError(ListDetailOperation.CLEAR_COMPLETED, failure.toRepositoryApplicationError())
            } finally {
                _isClearingCompleted.value = false
            }
        }
    }

    /**
     * Re-attempts whichever operation last failed, using [pendingRetryAddTitle]/
     * [pendingRetryItemId]/[pendingRetryToggleTarget] as needed. A no-op if nothing failed, if
     * the cached inputs are missing, or if a matching operation is already in flight -
     * [performAddItem]/[performToggle]/[performDelete]/[performRestore]/[clearCompleted] each
     * re-check their own guard.
     */
    fun retryFailedOperation() {
        val failed = _operationError.value ?: return
        when (failed.operation) {
            ListDetailOperation.ADD_ITEM -> pendingRetryAddTitle?.let { performAddItem(it) }
            ListDetailOperation.TOGGLE_COMPLETED -> {
                val itemId = pendingRetryItemId
                val target = pendingRetryToggleTarget
                if (itemId != null && target != null) performToggle(itemId, target)
            }
            ListDetailOperation.DELETE_ITEM -> pendingRetryItemId?.let { performDelete(it) }
            ListDetailOperation.RESTORE_ITEM -> pendingRetryItemId?.let { performRestore(it) }
            ListDetailOperation.CLEAR_COMPLETED -> clearCompleted()
            ListDetailOperation.DELETE_LIST -> deleteList()
        }
    }

    /** Clears a shown [operationError] without retrying. */
    fun dismissOperationError() {
        _operationError.value = null
    }

    private fun clearErrorFor(operation: ListDetailOperation) {
        if (_operationError.value?.operation == operation) _operationError.value = null
    }

    /**
     * Soft-deletes this screen's own list (as opposed to [performDelete]/
     * [performRestore], which act on one of its items). Mirrors [performDelete]'s
     * try/catch(`CancellationException` rethrown)/catch(`Throwable`)/finally and
     * duplicate-submit-guard shape - a second call while a delete is already in flight is a
     * no-op ([_isDeletingList] is set synchronously, before the coroutine is even launched).
     *
     * Previously this was a bare `viewModelScope.launch { listRepository.softDeleteList(listId);
     * listDeleted.value = true }` with no guard at all: a real Firestore failure (e.g.
     * `PERMISSION_DENIED` after session invalidation) rethrew as `RepositoryException`
     * uncaught through `viewModelScope`, crashing the app process - the identical crash class
     * fixed for this screen's listener-observation chain ([itemsLoadState]),
     * just on this mutation path instead. On failure, [_isDeletingList]
     * is still reset (`finally`), [listDeleted] is left `false` (the screen is never torn down
     * for a delete that did not actually happen), and a retryable [ListDetailOperationError] is
     * surfaced via [ListDetailOperation.DELETE_LIST] instead.
     */
    fun deleteList() {
        if (_isDeletingList.value) return
        _isDeletingList.value = true
        clearErrorFor(ListDetailOperation.DELETE_LIST)
        viewModelScope.launch {
            try {
                listRepository.softDeleteList(listId)
                listDeleted.value = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _operationError.value =
                    ListDetailOperationError(ListDetailOperation.DELETE_LIST, failure.toRepositoryApplicationError())
            } finally {
                _isDeletingList.value = false
            }
        }
    }
}
