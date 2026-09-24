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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val MAX_ITEM_NAME_LENGTH = 120
const val MAX_DESCRIPTION_LENGTH = 2000

/**
 * `FB-306`: which photo operation most recently failed, so [ItemDetailScreen] can show a
 * failure state distinct from "no photo"/"loading" and offer a retry affordance scoped to
 * exactly the operation that failed - a replace (pick/prepare/upload/persist/delete-old) or
 * a remove (clear reference/best-effort delete).
 */
enum class PhotoOperationKind { REPLACE, REMOVE }

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
    /** `FB-306`: true while a [ItemDetailViewModel.removePhoto] (or its retry) is in flight. */
    val isRemovingPhoto: Boolean = false,
    /**
     * `FB-306`: non-null when the most recent replace or remove attempt failed and has not
     * since been retried successfully or dismissed. Resolves `FB-302-NB3`/`FB-303-NB2`: an
     * uncaught exception from `replacePhoto`/`photoPreparer` (or now `removePhoto`'s document
     * write) used to propagate out of `viewModelScope.launch` with no user-facing state at
     * all - see [ItemDetailViewModel.pickPhoto]/[ItemDetailViewModel.removePhoto] for how it
     * is now caught and surfaced here instead. Cleared by starting a new operation, a
     * successful retry, or [ItemDetailViewModel.dismissPhotoError].
     */
    val photoOperationFailed: PhotoOperationKind? = null,
    val closed: Boolean = false,
) {
    val isValid: Boolean get() = title.trim().isNotEmpty()
    val isDirty: Boolean
        get() = item != null && (title != item.title || description != (item.description ?: ""))
    val canSave: Boolean get() = isDirty && isValid && !isSaving

    /** `FB-306`: true while either a replace or a remove (including their retries) is in
     * flight - the UI disables photo actions and shows progress for both under one flag. */
    val isPhotoBusy: Boolean get() = isPickingPhoto || isRemovingPhoto
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

    /**
     * `FB-306`: the raw bytes returned by the most recent [photoPicker] pick, retained across
     * a failure so [retryPhotoOperation] can redrive the full prepare-upload-persist-delete
     * pipeline ([performReplace]) without reopening the system photo picker. Cleared once a
     * replace commits successfully or a fresh pick starts. Deliberately the *raw* picked
     * bytes, not the [photoPreparer]-prepared ones: this lets a retry correctly re-attempt a
     * [com.fluxit.data.PhotoRejected] validation failure too (`FB-303-NB2`), not only an
     * upload/persist failure (`FB-302-NB3`) - `photoPreparer` is a pure function of the raw
     * bytes, so recomputing it on every attempt is cheap and always correct.
     */
    private var pendingReplaceBytes: ByteArray? = null

    /**
     * `FB-306`: the `photoRef` a failed [removePhoto] attempt was trying to clear, retained
     * so [retryPhotoOperation] can redrive exactly that removal.
     */
    private var pendingRemoveRef: String? = null

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
     * Picks a new photo and replaces the current one, if any, via [performReplace]. Resets
     * [ItemDetailUiState.isPickingPhoto] synchronously (before the system picker even opens,
     * matching the pre-`FB-306` guard against a double-tap launching two concurrent pickers)
     * and again once the whole attempt settles.
     *
     * If the user cancels the picker (`null` result), the operation simply ends with no
     * change and no error - cancelling is not a failure. Any other failure (a
     * `PhotoRejected` from [photoPreparer], `FB-303-NB2`, or an upload/document-write failure
     * from [performReplace], `FB-302-NB3`) is caught there and surfaced as
     * [ItemDetailUiState.photoOperationFailed] rather than propagating uncaught - see
     * [performReplace]'s KDoc for the exact failure/preservation semantics.
     */
    fun pickPhoto() {
        if (_uiState.value.isPhotoBusy) return
        _uiState.value = _uiState.value.copy(isPickingPhoto = true, photoOperationFailed = null)
        viewModelScope.launch {
            val pickedBytes = photoPicker.pickPhoto()
            if (pickedBytes == null) {
                _uiState.value = _uiState.value.copy(isPickingPhoto = false)
                return@launch
            }
            pendingReplaceBytes = pickedBytes
            performReplace(pickedBytes)
        }
    }

    /**
     * Re-attempts whichever photo operation last failed ([ItemDetailUiState.photoOperationFailed]),
     * using the inputs cached by [pendingReplaceBytes]/[pendingRemoveRef] - a no-op if nothing
     * is cached (e.g. called with no failure pending) or a photo/remove operation is already
     * in flight.
     */
    fun retryPhotoOperation() {
        if (_uiState.value.isPhotoBusy) return
        when (_uiState.value.photoOperationFailed) {
            PhotoOperationKind.REPLACE -> {
                val bytes = pendingReplaceBytes ?: return
                _uiState.value = _uiState.value.copy(isPickingPhoto = true, photoOperationFailed = null)
                viewModelScope.launch { performReplace(bytes) }
            }
            PhotoOperationKind.REMOVE -> {
                val ref = pendingRemoveRef ?: return
                beginRemove(ref)
            }
            null -> Unit
        }
    }

    /** Clears a shown [ItemDetailUiState.photoOperationFailed] without retrying - e.g. the
     * user dismisses the failure banner and picks a different photo, or simply moves on. */
    fun dismissPhotoError() {
        _uiState.value = _uiState.value.copy(photoOperationFailed = null)
    }

    /**
     * Validates/prepares [pickedBytes] via [photoPreparer] (`FB-303`: size/type limits and
     * resize/compression - see `PhotoPolicy.kt`), then replaces the current photo, if any,
     * following the safe-replace ordering [replacePhoto] documents (upload, then persist the
     * reference, then best-effort delete the old object - see `PhotoStorage`'s KDoc). The
     * prepared (not the raw picked) bytes are what get uploaded and shown as
     * [ItemDetailUiState.photoPreview].
     *
     * Always leaves [ItemDetailUiState.isPickingPhoto] `false` on exit (success or failure).
     * On success, clears [pendingReplaceBytes] and any shown
     * [ItemDetailUiState.photoOperationFailed]. On failure - [photoPreparer] rejecting the
     * bytes, or [replacePhoto] throwing from a failed upload or document write -
     * [ItemDetailUiState.photoRef]/[ItemDetailUiState.photoPreview] are left exactly as they
     * were before the call (the old, still valid photo is never overwritten with a
     * half-completed result - `replacePhoto`'s own documented ordering already guarantees
     * this at the storage/document layer; this only adds the UI-facing failure state) and
     * [ItemDetailUiState.photoOperationFailed] is set to [PhotoOperationKind.REPLACE] so
     * [ItemDetailScreen] can offer [retryPhotoOperation]. [pendingReplaceBytes] is left
     * intact on failure specifically so that retry is possible.
     *
     * `CancellationException` is deliberately rethrown, not swallowed into a failure state -
     * a cancelled coroutine (e.g. the ViewModel being cleared because the user navigated away)
     * is not a user-facing failure to report.
     */
    private suspend fun performReplace(pickedBytes: ByteArray) {
        try {
            val oldRef = _uiState.value.photoRef
            val prepared = photoPreparer(pickedBytes)
            val newRef = replacePhoto(
                storage = photoStorage,
                itemId = itemId,
                oldPhotoRef = oldRef,
                newBytes = prepared,
                updateRef = { ref -> itemRepository.setPhotoRef(listId, itemId, ref) },
            )
            pendingReplaceBytes = null
            _uiState.value = _uiState.value.copy(
                photoRef = newRef,
                photoPreview = PhotoContent.Bytes(prepared),
                photoOperationFailed = null,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            _uiState.value = _uiState.value.copy(photoOperationFailed = PhotoOperationKind.REPLACE)
        } finally {
            _uiState.value = _uiState.value.copy(isPickingPhoto = false)
        }
    }

    /**
     * Clears the item's photo reference first, then best-effort deletes the now-unreferenced
     * object - mirroring [replacePhoto]'s delete-last, swallow-delete-failure policy. If
     * clearing the reference itself fails, the failure is caught here (`FB-302-NB3`'s sibling
     * gap on the remove path) rather than propagating uncaught, [ItemDetailUiState.photoRef]
     * is left exactly as it was (the old photo remains fully referenced and loadable - no
     * delete is even attempted, since it only runs after the reference clear succeeds), and
     * [ItemDetailUiState.photoOperationFailed] is set to [PhotoOperationKind.REMOVE] so a
     * retry can be offered.
     */
    fun removePhoto() {
        val ref = _uiState.value.photoRef ?: return
        if (_uiState.value.isPhotoBusy) return
        pendingRemoveRef = ref
        beginRemove(ref)
    }

    private fun beginRemove(ref: String) {
        _uiState.value = _uiState.value.copy(isRemovingPhoto = true, photoOperationFailed = null)
        viewModelScope.launch {
            try {
                itemRepository.setPhotoRef(listId, itemId, null)
                pendingRemoveRef = null
                runCatching { photoStorage.deletePhoto(ref) }
                _uiState.value = _uiState.value.copy(photoRef = null, photoPreview = null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.value = _uiState.value.copy(photoOperationFailed = PhotoOperationKind.REMOVE)
            } finally {
                _uiState.value = _uiState.value.copy(isRemovingPhoto = false)
            }
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
