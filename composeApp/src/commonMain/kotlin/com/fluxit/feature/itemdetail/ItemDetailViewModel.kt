package com.fluxit.feature.itemdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.domain.FluxItem
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val MAX_ITEM_NAME_LENGTH = 120
const val MAX_DESCRIPTION_LENGTH = 2000

data class ItemDetailUiState(
    val item: FluxItem? = null,
    val listName: String = "",
    val title: String = "",
    val description: String = "",
    val photoPath: String? = null,
    val isSaving: Boolean = false,
    val isPickingPhoto: Boolean = false,
    val closed: Boolean = false,
) {
    val isValid: Boolean get() = title.trim().isNotEmpty()
    val isDirty: Boolean
        get() = item != null && (title != item.title || description != (item.description ?: ""))
    val canSave: Boolean get() = isDirty && isValid && !isSaving
}

class ItemDetailViewModel(
    private val listId: String,
    private val itemId: String,
    private val itemRepository: ItemRepository,
    private val listRepository: ListRepository,
    private val photoPicker: PhotoPicker,
    private val photoStorage: PhotoStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ItemDetailUiState())
    val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val item = itemRepository.observeItem(listId, itemId).first() ?: return@launch
            val listName = listRepository.observeList(item.listId).first()?.name ?: ""
            _uiState.value = ItemDetailUiState(
                item = item,
                listName = listName,
                title = item.title,
                description = item.description ?: "",
                photoPath = item.photoPath,
            )
        }
    }

    fun onTitleChange(title: String) {
        if (title.length <= MAX_ITEM_NAME_LENGTH) _uiState.value = _uiState.value.copy(title = title)
    }

    fun onDescriptionChange(description: String) {
        if (description.length <= MAX_DESCRIPTION_LENGTH) {
            _uiState.value = _uiState.value.copy(description = description)
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        _uiState.value = state.copy(isSaving = true)
        viewModelScope.launch {
            itemRepository.updateItem(
                listId,
                itemId,
                state.title.trim(),
                state.description.trim().ifEmpty { null },
            )
            _uiState.value = _uiState.value.copy(closed = true)
        }
    }

    fun pickPhoto() {
        if (_uiState.value.isPickingPhoto) return
        _uiState.value = _uiState.value.copy(isPickingPhoto = true)
        viewModelScope.launch {
            try {
                val bytes = photoPicker.pickPhoto() ?: return@launch
                val oldPath = _uiState.value.photoPath
                val path = photoStorage.savePhoto(bytes)
                itemRepository.setPhotoPath(listId, itemId, path)
                if (oldPath != null) photoStorage.deletePhoto(oldPath)
                _uiState.value = _uiState.value.copy(photoPath = path)
            } finally {
                _uiState.value = _uiState.value.copy(isPickingPhoto = false)
            }
        }
    }

    fun removePhoto() {
        val path = _uiState.value.photoPath ?: return
        viewModelScope.launch {
            itemRepository.setPhotoPath(listId, itemId, null)
            photoStorage.deletePhoto(path)
            _uiState.value = _uiState.value.copy(photoPath = null)
        }
    }

    fun deleteItem() {
        viewModelScope.launch {
            _uiState.value.photoPath?.let { photoStorage.deletePhoto(it) }
            itemRepository.deleteItem(listId, itemId)
            _uiState.value = _uiState.value.copy(closed = true)
        }
    }
}
