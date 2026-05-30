package dev.franzueto.fluxit.shared.state.store

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Optional
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.Item
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ItemPatch
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.usecase.items.DeleteItem
import dev.franzueto.fluxit.shared.domain.usecase.items.ObserveItem
import dev.franzueto.fluxit.shared.domain.usecase.items.UpdateItemDetails
import dev.franzueto.fluxit.shared.domain.usecase.photos.AttachPhotoToItem
import dev.franzueto.fluxit.shared.domain.usecase.photos.DetachPhotoFromItem
import dev.franzueto.fluxit.shared.domain.usecase.photos.PhotoSource
import dev.franzueto.fluxit.shared.domain.usecase.photos.ResolvePhotoUri
import dev.franzueto.fluxit.shared.state.error.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Store backing the Edit-Item screen (Phase 10; `plan/05` §4, ADR-014).
 *
 * **Feed.** [ItemDetailIntent.Init] launches [ObserveItem]; a `null` emission
 * (the item was deleted elsewhere) lands as [LoadState.Error]. While the user
 * hasn't started editing ([ItemDetailState.dirty] = false) the working-copy
 * [ItemDetailState.editing] is kept in sync with the observed item, so external
 * updates flow in; once dirty, local edits win until [ItemDetailIntent.SaveClicked].
 *
 * **Photo chain (§14).** [ItemDetailIntent.UpdatePhotoClicked] opens an in-state
 * action sheet ([ItemDetailState.showPhotoSourceSheet], not an effect, per §4);
 * [ItemDetailIntent.PhotoSourceSelected] calls [AttachPhotoToItem] (which
 * orchestrates capture → ingest → attach in one atomic use case). The store
 * surfaces a single **busy** state [PhotoStatus.Capturing] for that span, then
 * resolves the new photo to a uri ([PhotoStatus.Loaded]) once it lands.
 *
 * > **§14 three-state divergence:** [PhotoStatus.Uploading] is part of the
 * > contract but **unreachable today** — `AttachPhotoToItem` performs capture +
 * > ingest + attach as one suspend call, so the store can't observe the
 * > capture→upload boundary from outside. Surfacing `Uploading` separately needs
 * > that use case split into discrete capture + ingest steps; until then the whole
 * > acquire span shows as `Capturing`.
 *
 * A [CaptureError.UserCancelled] is a quiet abort (no error banner); a
 * [CaptureError.PermissionDenied] emits the matching `Request*` effect so the host
 * can prompt + retry.
 *
 * Navigation/permission prompts are one-shot [effects][ItemDetailEffect] (§14
 * default); `BackClicked` with unsaved edits emits [ItemDetailEffect.ConfirmDiscardChanges]
 * rather than navigating, so the host can surface a confirm dialog.
 */
public class ItemDetailStore(
    private val scope: CoroutineScope,
    logger: AppLogger,
    private val observeItem: ObserveItem,
    private val resolvePhotoUri: ResolvePhotoUri,
    private val updateItemDetails: UpdateItemDetails,
    private val deleteItem: DeleteItem,
    private val attachPhotoToItem: AttachPhotoToItem,
    private val detachPhotoFromItem: DetachPhotoFromItem,
) : BaseStore<ItemDetailState, ItemDetailIntent, ItemDetailEffect>(ItemDetailState(), scope, logger) {
    private var itemId: ItemId? = null
    private var feedJob: Job? = null

    override suspend fun reduce(intent: ItemDetailIntent) {
        when (intent) {
            is ItemDetailIntent.Init -> init(intent.itemId)
            ItemDetailIntent.BackClicked ->
                if (currentState.dirty) emit(ItemDetailEffect.ConfirmDiscardChanges) else emit(ItemDetailEffect.NavigateBack)
            ItemDetailIntent.SaveClicked -> save()
            is ItemDetailIntent.TitleChanged ->
                update { copy(editing = editing.copy(title = intent.title), dirty = true) }
            is ItemDetailIntent.DescriptionChanged ->
                update { copy(editing = editing.copy(description = intent.description), dirty = true) }
            ItemDetailIntent.UpdatePhotoClicked -> update { copy(showPhotoSourceSheet = true) }
            is ItemDetailIntent.PhotoSourceSelected -> attachPhoto(intent.source)
            ItemDetailIntent.RemovePhotoClicked -> removePhoto()
            ItemDetailIntent.DeleteClicked -> update { copy(confirmDelete = true) }
            ItemDetailIntent.CancelDelete -> update { copy(confirmDelete = false) }
            ItemDetailIntent.ConfirmDelete -> confirmDelete()
        }
    }

    private fun init(id: ItemId) {
        if (feedJob != null) return
        itemId = id
        feedJob =
            scope.launch {
                observeItem(id).collect { item ->
                    if (item == null) {
                        update { copy(item = LoadState.Error("This item is no longer available.")) }
                        return@collect
                    }
                    syncItem(item)
                    refreshPhoto(item)
                }
            }
    }

    /** Land the observed item; sync the working copy only while the user hasn't edited. */
    private fun syncItem(item: Item) {
        update {
            copy(
                item = LoadState.Loaded(item),
                editing = if (dirty) editing else item.toPatch(),
            )
        }
    }

    /** Resolve the item's photo to a uri unless a capture is mid-flight (don't clobber the busy state). */
    private suspend fun refreshPhoto(item: Item) {
        val status = currentState.photoStatus
        if (status is PhotoStatus.Capturing || status is PhotoStatus.Uploading) return
        val photoId = item.photoId
        val next =
            if (photoId == null) {
                PhotoStatus.None
            } else {
                resolvePhotoUri(photoId)?.let { PhotoStatus.Loaded(it) } ?: PhotoStatus.Error
            }
        update { copy(photoStatus = next) }
    }

    private suspend fun save() {
        val id = itemId ?: return
        val editing = currentState.editing
        val result =
            updateItemDetails(
                id,
                title = Optional.Set(editing.title),
                subtitle = Optional.Set(editing.subtitle),
                description = Optional.Set(editing.description),
            )
        when (result) {
            is Outcome.Ok -> {
                update { copy(dirty = false) }
                emit(ItemDetailEffect.NavigateBack)
            }
            is Outcome.Err -> emit(ItemDetailEffect.ShowError(result.error.userMessage))
        }
    }

    private suspend fun attachPhoto(source: PhotoPickSource) {
        val id = itemId ?: return
        val prior = currentState.photoStatus
        update { copy(showPhotoSourceSheet = false, photoStatus = PhotoStatus.Capturing) }
        val domainSource = if (source == PhotoPickSource.Camera) PhotoSource.CAMERA else PhotoSource.LIBRARY
        when (val result = attachPhotoToItem(id, domainSource)) {
            is Outcome.Ok -> {
                val status = resolvePhotoUri(result.value)?.let { PhotoStatus.Loaded(it) } ?: PhotoStatus.Error
                update { copy(photoStatus = status) }
            }
            is Outcome.Err -> handleAttachError(result.error, source, prior)
        }
    }

    private suspend fun handleAttachError(
        error: DomainError,
        source: PhotoPickSource,
        prior: PhotoStatus,
    ) {
        val reason = (error as? DomainError.CaptureFailure)?.reason
        when (reason) {
            // Quiet abort — restore the pre-capture status, no banner.
            CaptureError.UserCancelled -> update { copy(photoStatus = prior) }
            // Ask the host to prompt for the right permission, then retry.
            CaptureError.PermissionDenied -> {
                update { copy(photoStatus = prior) }
                emit(
                    if (source == PhotoPickSource.Camera) {
                        ItemDetailEffect.RequestCameraPermission
                    } else {
                        ItemDetailEffect.RequestPhotoLibraryAccess
                    },
                )
            }
            else -> {
                update { copy(photoStatus = PhotoStatus.Error) }
                emit(ItemDetailEffect.ShowError(error.userMessage))
            }
        }
    }

    private suspend fun removePhoto() {
        val id = itemId ?: return
        // The item flow re-emits with photoId = null → refreshPhoto sets None.
        if (detachPhotoFromItem(id) is Outcome.Err) {
            emit(ItemDetailEffect.ShowError("Couldn't remove the photo. Please try again."))
        }
    }

    private suspend fun confirmDelete() {
        val id = itemId ?: return
        when (val result = deleteItem(id)) {
            is Outcome.Ok -> emit(ItemDetailEffect.NavigateBack)
            is Outcome.Err -> {
                update { copy(confirmDelete = false) }
                emit(ItemDetailEffect.ShowError(result.error.userMessage))
            }
        }
    }

    private fun Item.toPatch(): ItemPatch = ItemPatch(title = title, subtitle = subtitle, description = description, photoId = photoId)
}

// ---- ItemDetailStore contract (§11: lives alongside its store). ----

public data class ItemDetailState(
    val item: LoadState<Item> = LoadState.Loading,
    val editing: ItemPatch = ItemPatch(title = "", subtitle = null, description = null, photoId = null),
    val dirty: Boolean = false,
    val photoStatus: PhotoStatus = PhotoStatus.None,
    val showPhotoSourceSheet: Boolean = false,
    val confirmDelete: Boolean = false,
)

/**
 * Photo preview status for the Edit-Item screen (§4/§14).
 *
 * `Capturing` is the single busy state shown for the whole acquire span;
 * `Uploading` is part of the contract but unreachable until `AttachPhotoToItem`
 * is split into discrete capture + ingest steps (see [ItemDetailStore]).
 */
public sealed interface PhotoStatus {
    public data object None : PhotoStatus

    public data class Loaded(
        val uri: String,
    ) : PhotoStatus

    public data object Capturing : PhotoStatus

    public data object Uploading : PhotoStatus

    public data object Error : PhotoStatus
}

/** Where a newly-attached photo comes from (state-layer mirror of the domain `PhotoSource`). */
public enum class PhotoPickSource {
    Camera,
    Library,
}

public sealed interface ItemDetailIntent {
    public data class Init(
        val itemId: ItemId,
    ) : ItemDetailIntent

    public data object BackClicked : ItemDetailIntent

    public data object SaveClicked : ItemDetailIntent

    public data class TitleChanged(
        val title: String,
    ) : ItemDetailIntent

    public data class DescriptionChanged(
        val description: String?,
    ) : ItemDetailIntent

    public data object UpdatePhotoClicked : ItemDetailIntent

    public data class PhotoSourceSelected(
        val source: PhotoPickSource,
    ) : ItemDetailIntent

    public data object RemovePhotoClicked : ItemDetailIntent

    public data object DeleteClicked : ItemDetailIntent

    public data object ConfirmDelete : ItemDetailIntent

    public data object CancelDelete : ItemDetailIntent
}

public sealed interface ItemDetailEffect {
    public data object NavigateBack : ItemDetailEffect

    /** `BackClicked` with unsaved edits — the host surfaces a discard-confirm dialog. */
    public data object ConfirmDiscardChanges : ItemDetailEffect

    public data object RequestCameraPermission : ItemDetailEffect

    public data object RequestPhotoLibraryAccess : ItemDetailEffect

    public data class ShowError(
        val message: String,
    ) : ItemDetailEffect
}
