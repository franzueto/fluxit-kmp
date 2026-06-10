package com.fluxit.feature.createlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
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

    fun save() {
        val state = _uiState.value
        if (!state.isValid || state.isSaving) return
        _uiState.value = state.copy(isSaving = true)
        viewModelScope.launch {
            val name = state.name.trim()
            if (editingId != null) {
                listRepository.updateList(editingId, name, state.icon, state.color)
                _uiState.value = _uiState.value.copy(savedListId = "")
            } else {
                val id = listRepository.createList(name, state.icon, state.color)
                _uiState.value = _uiState.value.copy(savedListId = id)
            }
        }
    }
}
