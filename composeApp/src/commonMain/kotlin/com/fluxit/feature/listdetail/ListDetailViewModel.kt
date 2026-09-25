package com.fluxit.feature.listdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * `FB-403`: which item-level mutation most recently failed, so the UI can offer a scoped retry
 * via [ListDetailViewModel.retryFailedOperation] instead of a swallowed or uncaught exception -
 * exact counterpart of `DashboardOperation` (`FB-402`), scoped to this screen's operations.
 */
enum class ListDetailOperation { ADD_ITEM, TOGGLE_COMPLETED, DELETE_ITEM, RESTORE_ITEM, CLEAR_COMPLETED }

/** `FB-403`: pairs the failed operation with FB-401's neutral, Firebase-free [ApplicationError]. */
data class ListDetailOperationError(
    val operation: ListDetailOperation,
    val error: ApplicationError,
)

data class ListDetailUiState(
    val list: FluxList? = null,
    val activeItems: List<FluxItem> = emptyList(),
    val completedItems: List<FluxItem> = emptyList(),
    val showCompleted: Boolean = true,
    val composerText: String = "",
    val listDeleted: Boolean = false,
) {
    val totalCount: Int get() = activeItems.size + completedItems.size
    val completedCount: Int get() = completedItems.size
}

class ListDetailViewModel(
    private val listId: String,
    private val listRepository: ListRepository,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val showCompleted = MutableStateFlow(true)
    private val composerText = MutableStateFlow("")
    private val listDeleted = MutableStateFlow(false)

    private val pendingUndo = MutableStateFlow<String?>(null)
    val undoItemId: StateFlow<String?> = pendingUndo.asStateFlow()
    private var undoJob: Job? = null

    /**
     * `FB-403`: item ids with a toggle/delete/restore currently in flight (including a retry).
     * Lets the UI disable that row's actions and doubles as this ViewModel's duplicate-submit
     * guard - see [performToggle]/[performDelete]/[performRestore]. Exposed as its own
     * [StateFlow], the same shape [undoItemId] already uses in this file, rather than folded
     * into [ListDetailUiState] - that data class's backing `combine` is already at Kotlin's
     * 5-flow direct-overload ceiling.
     */
    private val _pendingItemIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingItemIds: StateFlow<Set<String>> = _pendingItemIds.asStateFlow()

    /** `FB-403`: true while [submitComposer]'s `addItem` (or its retry) is in flight. */
    private val _isAddingItem = MutableStateFlow(false)
    val isAddingItem: StateFlow<Boolean> = _isAddingItem.asStateFlow()

    /** `FB-403`: true while [clearCompleted] (or its retry) is in flight. */
    private val _isClearingCompleted = MutableStateFlow(false)
    val isClearingCompleted: StateFlow<Boolean> = _isClearingCompleted.asStateFlow()

    /**
     * `FB-403`: non-null when the most recent add/toggle/delete/restore/clear-completed attempt
     * failed and has not since been retried successfully or dismissed via [dismissOperationError].
     */
    private val _operationError = MutableStateFlow<ListDetailOperationError?>(null)
    val operationError: StateFlow<ListDetailOperationError?> = _operationError.asStateFlow()

    /** `FB-403`: inputs a failed operation needs to retry - only ever one operation's worth is
     * live at a time, since [_operationError] itself is a single slot. */
    private var pendingRetryAddTitle: String? = null
    private var pendingRetryItemId: String? = null
    private var pendingRetryToggleTarget: Boolean? = null

    val uiState: StateFlow<ListDetailUiState> = combine(
        listRepository.observeList(listId),
        itemRepository.observeItems(listId),
        showCompleted,
        composerText,
        listDeleted,
    ) { list, items, show, text, deleted ->
        ListDetailUiState(
            list = list,
            activeItems = items.filter { !it.isCompleted },
            completedItems = items.filter { it.isCompleted },
            showCompleted = show,
            composerText = text,
            listDeleted = deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDetailUiState())

    fun onComposerChange(text: String) {
        composerText.value = text
    }

    /**
     * `FB-403`: a second call while an add is already in flight is a no-op -
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
                    ListDetailOperationError(ListDetailOperation.ADD_ITEM, failure.toListDetailApplicationError())
            } finally {
                _isAddingItem.value = false
            }
        }
    }

    fun toggleCompleted(item: FluxItem) {
        performToggle(item.id, !item.isCompleted)
    }

    /** `FB-403`: mirrors [performDelete]'s try/finally and duplicate-guard shape. */
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
                    ListDetailOperationError(ListDetailOperation.TOGGLE_COMPLETED, failure.toListDetailApplicationError())
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
     * `FB-403`: soft-deletes [itemId] and, on success, starts the five-second undo window. A
     * second call for the *same* [itemId] while the first is still in flight is a no-op, per
     * [_pendingItemIds] (checked and updated synchronously, before the coroutine is even
     * launched). On failure, [_pendingItemIds] is still reset (`finally`) and a retryable
     * [ListDetailOperationError] is surfaced; [pendingUndo] is only ever set on success (a
     * correctness fix over the prior optimistic-before-the-call assignment - the same fix
     * `DashboardViewModel.performDelete`, `FB-402`, already made for lists), so a failed delete
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
                    ListDetailOperationError(ListDetailOperation.DELETE_ITEM, failure.toListDetailApplicationError())
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

    /** `FB-403`: mirrors [performDelete]'s try/finally and duplicate-guard shape for restore. */
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
                    ListDetailOperationError(ListDetailOperation.RESTORE_ITEM, failure.toListDetailApplicationError())
            } finally {
                _pendingItemIds.update { it - itemId }
            }
        }
    }

    fun dismissUndo() {
        pendingUndo.value = null
    }

    /**
     * `FB-403`: a second call while a clear is already in flight is a no-op -
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
                    ListDetailOperationError(ListDetailOperation.CLEAR_COMPLETED, failure.toListDetailApplicationError())
            } finally {
                _isClearingCompleted.value = false
            }
        }
    }

    /**
     * `FB-403`: re-attempts whichever operation last failed, using [pendingRetryAddTitle]/
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
        }
    }

    /** Clears a shown [operationError] without retrying. */
    fun dismissOperationError() {
        _operationError.value = null
    }

    private fun clearErrorFor(operation: ListDetailOperation) {
        if (_operationError.value?.operation == operation) _operationError.value = null
    }

    fun deleteList() {
        viewModelScope.launch {
            listRepository.softDeleteList(listId)
            listDeleted.value = true
        }
    }
}

/**
 * `FB-403`: see the identically-documented helper in `DashboardViewModel.kt`/
 * `CreateListViewModel.kt` (`FB-402`) - this ViewModel only ever calls [ItemRepository]/
 * [ListRepository], both of which have the same `commonMain`/platform-`internal` visibility
 * gap (`FB-402-NB1`, not this task's scope to close). Conservatively reported as
 * [RepositoryErrorCode.UNKNOWN] (`canRetry = true`) rather than a guessed, more specific code.
 */
private fun Throwable.toListDetailApplicationError(): ApplicationError =
    RepositoryErrorCode.UNKNOWN.toApplicationError()
