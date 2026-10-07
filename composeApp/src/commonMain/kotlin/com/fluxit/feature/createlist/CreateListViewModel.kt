package com.fluxit.feature.createlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.toRepositoryApplicationError
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val MAX_LIST_NAME_LENGTH = 60

data class CreateListUiState(
    val name: String = "",
    val icon: ListIcon = ListIcon.CART,
    val color: ListColor = ListColor.PRIMARY_BLUE,
    val initialName: String = "",
    val initialIcon: ListIcon = ListIcon.CART,
    val initialColor: ListColor = ListColor.PRIMARY_BLUE,
    val isEditMode: Boolean = false,
    val isSaving: Boolean = false,
    /** Set after save: created list id (create mode) or "" (edit mode saved). */
    val savedListId: String? = null,
    /**
     * Non-null when the most recent [CreateListViewModel.save] attempt failed and has
     * not since been retried successfully or dismissed via [CreateListViewModel.dismissError].
     * Neutral, Firebase-free [ApplicationError] - never a raw SDK exception.
     */
    val error: ApplicationError? = null,
) {
    val isValid: Boolean get() = name.trim().isNotEmpty() && name.length <= MAX_LIST_NAME_LENGTH
    val isDirty: Boolean get() = name != initialName || icon != initialIcon || color != initialColor
}

class CreateListViewModel(
    private val editingId: String?,
    private val listRepository: ListRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateListUiState(isEditMode = editingId != null))
    val uiState: StateFlow<CreateListUiState> = _uiState.asStateFlow()

    init {
        if (editingId != null) {
            viewModelScope.launch {
                val list = listRepository.observeList(editingId).first() ?: return@launch
                _uiState.value = CreateListUiState(
                    name = list.name,
                    icon = list.icon,
                    color = list.color,
                    initialName = list.name,
                    initialIcon = list.icon,
                    initialColor = list.color,
                    isEditMode = true,
                )
            }
        }
    }

    fun onNameChange(name: String) {
        if (name.length <= MAX_LIST_NAME_LENGTH) _uiState.value = _uiState.value.copy(name = name)
    }

    fun onIconChange(icon: ListIcon) {
        _uiState.value = _uiState.value.copy(icon = icon)
    }

    fun onColorChange(color: ListColor) {
        _uiState.value = _uiState.value.copy(color = color)
    }

    /**
     * Creates or updates the list. A second call while the first is still in flight (a
     * double-tap, or a retry racing a fresh tap) is a no-op - [CreateListUiState.isSaving] is set
     * synchronously, before the coroutine is even launched, so the guard below always sees the
     * first call's flag, exactly like `ItemDetailViewModel.pickPhoto`'s pre-existing
     * `isPickingPhoto` guard.
     *
     * On failure, `isSaving` is still reset (`finally`) and a retryable [CreateListUiState.error]
     * is surfaced instead of the flag being left stuck `true` forever with no feedback -
     * previously an uncaught exception here left [CreateListUiState.isSaving] permanently `true`,
     * locking the Save button with no recourse.
     */
    fun save() {
        val state = _uiState.value
        if (!state.isValid || state.isSaving) return
        _uiState.value = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                val name = state.name.trim()
                if (editingId != null) {
                    listRepository.updateList(editingId, name, state.icon, state.color)
                    _uiState.value = _uiState.value.copy(savedListId = "")
                } else {
                    val id = listRepository.createList(name, state.icon, state.color)
                    _uiState.value = _uiState.value.copy(savedListId = id)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.value = _uiState.value.copy(error = failure.toRepositoryApplicationError())
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    /**
     * Re-attempts [save] with the current (possibly since-edited) field values - a
     * no-op if nothing failed or a save is already in flight, exactly like [save] itself.
     */
    fun retrySave() = save()

    /** Clears a shown [CreateListUiState.error] without retrying. */
    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
