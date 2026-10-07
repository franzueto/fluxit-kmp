package com.fluxit.feature.itemdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.PhotoContent
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.data.PhotoStorageException
import com.fluxit.data.preparePhotoForUpload
import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toRepositoryApplicationError
import com.fluxit.data.replacePhoto
import com.fluxit.domain.FluxItem
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val MAX_ITEM_NAME_LENGTH = 120
const val MAX_DESCRIPTION_LENGTH = 2000

/**
 * Which photo operation most recently failed, so [ItemDetailScreen] can show a
 * failure state distinct from "no photo"/"loading" and offer a retry affordance scoped to
 * exactly the operation that failed - a replace (pick/prepare/upload/persist/delete-old) or
 * a remove (clear reference/best-effort delete).
 */
enum class PhotoOperationKind { REPLACE, REMOVE }

data class ItemDetailUiState(
    /**
     * True only until the initial load (session check + item fetch) in
     * [ItemDetailViewModel]'s `init` block settles, one way or another. Unlike
     * [com.fluxit.feature.dashboard.DashboardViewModel]/[com.fluxit.feature.listdetail.ListDetailViewModel],
     * this screen's item/title/description fields are a one-shot form populate (deliberately
     * *not* a live subscription - a live server push would otherwise clobber in-progress local
     * edits to [title]/[description] while the user is typing), so this flag is set once from
     * that one-shot load rather than derived from an ongoing [kotlinx.coroutines.flow.combine].
     */
    val isLoading: Boolean = true,
    /**
     * True once the initial load found the auth session backing this screen was not
     * [com.fluxit.domain.auth.AuthSession.Authenticated] - reused verbatim from /
     * Session machinery, not a parallel signal invented for this task. See
     * `DashboardViewModel`'s identically-purposed [com.fluxit.domain.ScreenLoadState.FatalSession]
     * KDoc for why this is defense-in-depth rather than the primary mechanism that reacts to a
     * session becoming invalid.
     */
    val isFatalSession: Boolean = false,
    /**
     * True once the initial load resolved with no item at [ItemDetailViewModel]'s
     * `listId`/`itemId` (deleted from another device, a stale deep link, or similar) - this
     * screen's analogue of a list-of-X screen's "loaded and genuinely empty" state: a
     * single-document view has nothing to distinguish "empty" from "not found," so this is the
     * one state, not two.
     */
    val notFound: Boolean = false,
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
    /**
     * Non-null when the most recent [ItemDetailViewModel.save] attempt failed and has
     * not since been retried successfully or dismissed via [ItemDetailViewModel.dismissSaveError].
     * Neutral, Firebase-free [ApplicationError] - previously an uncaught exception here
     * left [isSaving] permanently `true` with no feedback at all, the same bug fixed for
     * `CreateListViewModel.save`.
     */
    val saveError: ApplicationError? = null,
    val isPickingPhoto: Boolean = false,
    /** True while a [ItemDetailViewModel.removePhoto] (or its retry) is in flight. */
    val isRemovingPhoto: Boolean = false,
    /**
     * Non-null when the most recent replace or remove attempt failed and has not
     * since been retried successfully or dismissed. Resolves An
     * uncaught exception from `replacePhoto`/`photoPreparer` (or now `removePhoto`'s document
     * write) used to propagate out of `viewModelScope.launch` with no user-facing state at
     * all - see [ItemDetailViewModel.pickPhoto]/[ItemDetailViewModel.removePhoto] for how it
     * is now caught and surfaced here instead. Cleared by starting a new operation, a
     * successful retry, or [ItemDetailViewModel.dismissPhotoError].
     */
    val photoOperationFailed: PhotoOperationKind? = null,
    /**
     * Neutral, Firebase-free [ApplicationError] paired with
     * [photoOperationFailed] - additive alongside the pre-existing enum field (rather than
     * replacing its type) so `ItemDetailScreen`'s existing `PhotoOperationKind`-typed rendering
     * keeps compiling unchanged, matching the established "new fields are purely
     * additive" precedent. `AndroidPhotoStorage`/`IosPhotoStorage` now throw
     * [com.fluxit.data.PhotoStorageException] carrying exactly this type; an `ItemRepository` failure carries the error of its
     * [com.fluxit.data.remote.RepositoryException], and anything else falls back to
     * [RepositoryErrorCode.UNKNOWN] - see [toItemDetailApplicationError].
     */
    val photoOperationError: ApplicationError? = null,
    val closed: Boolean = false,
    /** True while [ItemDetailViewModel.deleteItem] (or its retry) is in flight. */
    val isDeletingItem: Boolean = false,
    /**
     * Non-null when the most recent [ItemDetailViewModel.deleteItem] attempt failed
     * and has not since been retried successfully or dismissed via
     * [ItemDetailViewModel.dismissDeleteError].
     */
    val deleteError: ApplicationError? = null,
) {
    val isValid: Boolean get() = title.trim().isNotEmpty()
    val isDirty: Boolean
        get() = item != null && (title != item.title || description != (item.description ?: ""))
    val canSave: Boolean get() = isDirty && isValid && !isSaving

    /** True while either a replace or a remove (including their retries) is in
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
     * Checked once, at the start of the `init` block's one-shot load, to derive
     * [ItemDetailUiState.isFatalSession] - see that field's KDoc for why this screen checks the
     * session once rather than continuously combining it, unlike
     * [com.fluxit.feature.dashboard.DashboardViewModel]/[com.fluxit.feature.listdetail.ListDetailViewModel].
     */
    private val authRepository: AuthRepository,
    /**
     * Validates and, if needed, resizes/recompresses freshly picked bytes before
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
     * The raw bytes returned by the most recent [photoPicker] pick, retained across
     * a failure so [retryPhotoOperation] can redrive the full prepare-upload-persist-delete
     * pipeline ([performReplace]) without reopening the system photo picker. Cleared once a
     * replace commits successfully or a fresh pick starts. Deliberately the *raw* picked
     * bytes, not the [photoPreparer]-prepared ones: this lets a retry correctly re-attempt a
     * [com.fluxit.data.PhotoRejected] validation failure too, not only an
     * upload/persist failure - `photoPreparer` is a pure function of the raw
     * bytes, so recomputing it on every attempt is cheap and always correct.
     */
    private var pendingReplaceBytes: ByteArray? = null

    /**
     * The `photoRef` a failed [removePhoto] attempt was trying to clear, retained
     * so [retryPhotoOperation] can redrive exactly that removal.
     */
    private var pendingRemoveRef: String? = null

    /**
     * Initial load - session check, then item fetch. `authRepository.session.first
     * { it !is AuthSession.Unresolved }` mirrors `DashboardViewModel`/`ListDetailViewModel`'s
     * `AuthSession.Authenticated` check: this ViewModel is only ever constructed once
     * `SessionGate` has already reached `Ready`, so in production this
     * resolves immediately - the wait guards the narrow, already-accepted race the sibling
     * ViewModels also guard against, not a real steady-state wait. [ItemDetailUiState.isLoading]
     * is left `true` (its default) on every path until this block reaches a terminal outcome -
     * fatal session, not-found, or a populated item - so a caller can never observe a state that
     * is neither loading nor resolved.
     *
     * `itemRepository.observeItem(listId, itemId).first`/
     * `listRepository.observeList(item.listId).first()` below collect a `callbackFlow`-backed
     * repository observation exactly like `DashboardViewModel`/`ListDetailViewModel`'s
     * `combine(...).stateIn(...)` chains, and are equally subject to the headline
     * finding: a terminal listener error (`close(exception)`) rethrows uncaught through
     * `viewModelScope` and crashes the app process, rather than through `Flow.catch` (that
     * operator does not apply here since these are one-shot `.first()` suspend calls, not a
     * continuously-collected `stateIn` flow) - so the fix is a plain `try`/`catch` around the
     * whole post-session-check load, mirroring this same file's [save]/[performReplace]/
     * [deleteItem] try/catch shape rather than inventing a new pattern. This is the same
     * uncaught-listener-error defect the sibling ViewModels guard against, in a one-shot
     * shape. Mapped to the pre-existing
     * [ItemDetailUiState.isFatalSession] flag for the same reasons `DashboardViewModel`'s
     * `.catch` KDoc discloses for [ScreenLoadState.FatalSession] - this screen's own
     * [ItemDetailUiState.isFatalSession] already exists for exactly this "session/access is not
     * currently viable" presentation, so no new field is introduced.
     */
    init {
        viewModelScope.launch {
            val session = authRepository.session.first { it !is AuthSession.Unresolved }
            if (session !is AuthSession.Authenticated) {
                _uiState.value = _uiState.value.copy(isLoading = false, isFatalSession = true)
                return@launch
            }
            try {
                val item = itemRepository.observeItem(listId, itemId).first()
                if (item == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, notFound = true)
                    return@launch
                }
                val listName = listRepository.observeList(item.listId).first()?.name ?: ""
                _uiState.value = ItemDetailUiState(
                    isLoading = false,
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
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.value = _uiState.value.copy(isLoading = false, isFatalSession = true)
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

    /**
     * A second call while a save is already in flight is a no-op - [state.canSave]
     * already requires `!isSaving`, checked synchronously before [ItemDetailUiState.isSaving]
     * is itself set, so the guard always sees the first call's flag, exactly like
     * `CreateListViewModel.save`'s identical guard.
     *
     * On failure, `isSaving` is still reset (`finally`) and a retryable
     * [ItemDetailUiState.saveError] is surfaced instead of the flag being left stuck `true`
     * forever with no feedback - previously an uncaught exception here left
     * [ItemDetailUiState.isSaving] permanently `true`, locking the Save button with no
     * recourse (the exact bug fixed for `CreateListViewModel.save`).
     */
    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        _uiState.value = state.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            try {
                itemRepository.updateItem(
                    listId,
                    itemId,
                    state.title.trim(),
                    state.description.trim().ifEmpty { null },
                )
                _uiState.value = _uiState.value.copy(closed = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.value = _uiState.value.copy(saveError = failure.toItemDetailApplicationError())
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    /** Re-attempts [save] with the current (possibly since-edited) field values - a
     * no-op if nothing failed or a save is already in flight, exactly like [save] itself. */
    fun retrySave() = save()

    /** Clears a shown [ItemDetailUiState.saveError] without retrying. */
    fun dismissSaveError() {
        _uiState.value = _uiState.value.copy(saveError = null)
    }

    /**
     * Picks a new photo and replaces the current one, if any, via [performReplace]. Resets
     * [ItemDetailUiState.isPickingPhoto] synchronously (before the system picker even opens,
     * matching the pre-fix guard against a double-tap launching two concurrent pickers)
     * and again once the whole attempt settles.
     *
     * If the user cancels the picker (`null` result), the operation simply ends with no
     * change and no error - cancelling is not a failure. Any other failure (a
     * `PhotoRejected` from [photoPreparer], or an upload/document-write failure
     * from [performReplace]) is caught there and surfaced as
     * [ItemDetailUiState.photoOperationFailed] rather than propagating uncaught - see
     * [performReplace]'s KDoc for the exact failure/preservation semantics.
     */
    fun pickPhoto() {
        if (_uiState.value.isPhotoBusy) return
        _uiState.value = _uiState.value.copy(isPickingPhoto = true, photoOperationFailed = null, photoOperationError = null)
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
                _uiState.value =
                    _uiState.value.copy(isPickingPhoto = true, photoOperationFailed = null, photoOperationError = null)
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
        _uiState.value = _uiState.value.copy(photoOperationFailed = null, photoOperationError = null)
    }

    /**
     * Validates/prepares [pickedBytes] via [photoPreparer] (Size/type limits and
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
                photoOperationError = null,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            _uiState.value = _uiState.value.copy(
                photoOperationFailed = PhotoOperationKind.REPLACE,
                photoOperationError = failure.toItemDetailApplicationError(),
            )
        } finally {
            _uiState.value = _uiState.value.copy(isPickingPhoto = false)
        }
    }

    /**
     * Clears the item's photo reference first, then best-effort deletes the now-unreferenced
     * object - mirroring [replacePhoto]'s delete-last, swallow-delete-failure policy. If
     * clearing the reference itself fails, the failure is caught here (the sibling
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
        _uiState.value = _uiState.value.copy(isRemovingPhoto = true, photoOperationFailed = null, photoOperationError = null)
        viewModelScope.launch {
            try {
                itemRepository.setPhotoRef(listId, itemId, null)
                pendingRemoveRef = null
                runCatching { photoStorage.deletePhoto(ref) }
                _uiState.value = _uiState.value.copy(photoRef = null, photoPreview = null)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.value = _uiState.value.copy(
                    photoOperationFailed = PhotoOperationKind.REMOVE,
                    photoOperationError = failure.toItemDetailApplicationError(),
                )
            } finally {
                _uiState.value = _uiState.value.copy(isRemovingPhoto = false)
            }
        }
    }

    /**
     * A second call while a delete is already in flight is a no-op -
     * [ItemDetailUiState.isDeletingItem] is set synchronously, before the coroutine is even
     * launched. On failure, the flag is still reset (`finally`) and a retryable
     * [ItemDetailUiState.deleteError] is surfaced instead of an uncaught exception from
     * `viewModelScope.launch`. The best-effort photo cleanup below is unchanged - a Storage
     * hiccup there is already swallowed via `runCatching` and must not block deleting the item
     * itself; only a genuine [itemRepository.deleteItem] failure reaches the `catch` below.
     */
    fun deleteItem() {
        if (_uiState.value.isDeletingItem) return
        _uiState.value = _uiState.value.copy(isDeletingItem = true, deleteError = null)
        viewModelScope.launch {
            try {
                // Best-effort cleanup: a Storage hiccup must not block deleting the item itself.
                // Reliable cascade cleanup on item deletion is the job, not this one's.
                _uiState.value.photoRef?.let { ref -> runCatching { photoStorage.deletePhoto(ref) } }
                itemRepository.deleteItem(listId, itemId)
                _uiState.value = _uiState.value.copy(closed = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.value = _uiState.value.copy(deleteError = failure.toItemDetailApplicationError())
            } finally {
                _uiState.value = _uiState.value.copy(isDeletingItem = false)
            }
        }
    }

    /** Re-attempts [deleteItem] - a no-op if nothing failed or a delete is already in
     * flight, exactly like [deleteItem] itself. */
    fun retryDeleteItem() = deleteItem()

    /** Clears a shown [ItemDetailUiState.deleteError] without retrying. */
    fun dismissDeleteError() {
        _uiState.value = _uiState.value.copy(deleteError = null)
    }
}

/** Photo-storage failures carry their own mapped error; everything else is a repository failure. */
private fun Throwable.toItemDetailApplicationError(): ApplicationError = when (this) {
    is PhotoStorageException -> error
    else -> toRepositoryApplicationError()
}
