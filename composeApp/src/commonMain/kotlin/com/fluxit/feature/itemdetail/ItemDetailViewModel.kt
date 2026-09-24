package com.fluxit.feature.itemdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.PhotoContent
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.data.preparePhotoForUpload
import com.fluxit.data.replacePhoto
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
    val photoRef: String? = null,
    /** Renderable form of [photoRef], resolved via [PhotoStorage.loadPhoto] (or set directly
     * from freshly picked bytes on a successful upload). Null whenever [photoRef] is null,
     * or while it has not been resolved/is unresolvable. */
    val photoPreview: PhotoContent? = null,
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
    /**
     * `FB-303`: validates and, if needed, resizes/recompresses freshly picked bytes before
     * [pickPhoto] ever calls [uploadPhoto]/[replacePhoto] - see `PhotoPolicy.kt`'s
     * [preparePhotoForUpload] for the enforced size/type limits and resize decision logic.
     * Defaults to the real [preparePhotoForUpload] (itself backed by the real platform
     * [com.fluxit.data.readImageDimensions]/[com.fluxit.data.resizeImage] expect/actual pair)
     * for production/Koin wiring; tests inject a fake so `commonTest` never has to exercise a
     * real platform image decoder (Android's `testDebugUnitTest` has no Robolectric and would
     * fail on a real `BitmapFactory` call).
     */
    private val photoPreparer: (ByteArray) -> ByteArray = ::preparePhotoForUpload,
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
                photoRef = item.photoRef,
            )
            val ref = item.photoRef
            if (ref != null) {
                val preview = runCatching { photoStorage.loadPhoto(ref) }.getOrNull()
                _uiState.value = _uiState.value.copy(photoPreview = preview)
            }
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

    /**
     * Picks a new photo, validates/prepares it via [photoPreparer] (`FB-303`: size/type limits
     * and resize/compression - see `PhotoPolicy.kt`), and replaces the current photo, if any,
     * following the safe-replace ordering [replacePhoto] documents (upload, then persist the
     * reference, then best-effort delete the old object - see `PhotoStorage`'s KDoc). The
     * prepared (not the raw picked) bytes are what get uploaded and shown as
     * [ItemDetailUiState.photoPreview].
     *
     * If [photoPreparer] rejects the picked bytes (a typed `PhotoRejected` from `PhotoPolicy.kt`)
     * or [replacePhoto] throws (upload or document-write failure), this coroutine's exception
     * propagates uncaught and [_uiState] is left exactly as it was before the call - the old,
     * still valid [ItemDetailUiState.photoRef]/[ItemDetailUiState.photoPreview] are never
     * overwritten with a half-completed result. Retry/error UI affordances for either failure
     * are `FB-306`/`FB-403`'s scope, not this one's (mirrors `FB-302-NB3`, not re-litigated here).
     */
    fun pickPhoto() {
        if (_uiState.value.isPickingPhoto) return
        _uiState.value = _uiState.value.copy(isPickingPhoto = true)
        viewModelScope.launch {
            try {
                val pickedBytes = photoPicker.pickPhoto() ?: return@launch
                val bytes = photoPreparer(pickedBytes)
                val oldRef = _uiState.value.photoRef
                val newRef = replacePhoto(
                    storage = photoStorage,
                    itemId = itemId,
                    oldPhotoRef = oldRef,
                    newBytes = bytes,
                    updateRef = { ref -> itemRepository.setPhotoRef(listId, itemId, ref) },
                )
                _uiState.value = _uiState.value.copy(
                    photoRef = newRef,
                    photoPreview = PhotoContent.Bytes(bytes),
                )
            } finally {
                _uiState.value = _uiState.value.copy(isPickingPhoto = false)
            }
        }
    }

    /**
     * Clears the item's photo reference first, then best-effort deletes the now-unreferenced
     * object - mirroring [replacePhoto]'s delete-last, swallow-delete-failure policy. If
     * clearing the reference itself fails, this throws before any delete is attempted and
     * the old photo remains fully referenced and loadable.
     */
    fun removePhoto() {
        val ref = _uiState.value.photoRef ?: return
        viewModelScope.launch {
            itemRepository.setPhotoRef(listId, itemId, null)
            runCatching { photoStorage.deletePhoto(ref) }
            _uiState.value = _uiState.value.copy(photoRef = null, photoPreview = null)
        }
    }

    fun deleteItem() {
        viewModelScope.launch {
            // Best-effort cleanup: a Storage hiccup must not block deleting the item itself.
            // Reliable cascade cleanup on item deletion is FB-502/FB-503's job, not this one's.
            _uiState.value.photoRef?.let { ref -> runCatching { photoStorage.deletePhoto(ref) } }
            itemRepository.deleteItem(listId, itemId)
            _uiState.value = _uiState.value.copy(closed = true)
        }
    }
}
