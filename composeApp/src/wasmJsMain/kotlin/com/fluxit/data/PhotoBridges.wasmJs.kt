package com.fluxit.data

import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.WebAuthBridgeCurrentUidProvider
import com.fluxit.firebase.storage.JsWebFirebaseStorageBridge
import com.fluxit.firebase.storage.PhotoStorageWebException
import com.fluxit.firebase.storage.WebFirebaseStorageBridge
import com.fluxit.firebase.storage.isStorageObjectNotFound
import com.fluxit.firebase.storage.toApplicationError
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.HTMLInputElement
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.files.get

/**
 * Opens the browser's file chooser (a file input accepting any image type; on a phone, the
 * photo library or camera) and returns the picked file's bytes, or `null` if cancelled.
 *
 * At most [PhotoPolicy.MAX_SOURCE_BYTES] + 1 bytes are read, so an oversized file costs no
 * more memory than the limit and is still rejected as [PhotoRejected.TooLarge] by
 * [preparePhotoForUpload]. Type checks stay with [validatePhotoSource] (magic bytes, not the
 * browser's MIME type); iOS Safari hands HEIC library photos over as JPEG when any image type is accepted.
 */
class WebPhotoPicker : PhotoPicker {

    override suspend fun pickPhoto(): ByteArray? {
        val file = chooseFile() ?: return null
        val buffer = suspendCancellableCoroutine { continuation ->
            readBlob(file.slice(0, PhotoPolicy.MAX_SOURCE_BYTES + 1)) { continuation.resume(it) }
        }
        return buffer?.toByteArray()
    }

    private suspend fun chooseFile(): File? = suspendCancellableCoroutine { continuation ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = "image/*"
        input.style.display = "none"
        fun finish(file: File?) {
            input.remove()
            if (continuation.isActive) continuation.resume(file)
        }
        input.addEventListener("change", { finish(input.files?.get(0)) })
        // Fired when the chooser closes without a selection (all browsers with Wasm GC).
        input.addEventListener("cancel", { finish(null) })
        continuation.invokeOnCancellation { input.remove() }
        // Attached while open: some mobile browsers drop events from a detached input.
        document.body?.appendChild(input)
        input.click()
    }
}

/** Reads [blob] into memory; `null` if the browser cannot read it (for example a file deleted since picking). */
@JsFun("(blob, done) => { blob.arrayBuffer().then((buffer) => done(buffer), () => done(null)); }")
private external fun readBlob(blob: Blob, done: (ArrayBuffer?) -> Unit)

/**
 * Real Cloud Storage-backed [PhotoStorage], ported from `IosPhotoStorage`: every object is
 * addressed by the exact `photoRef` string [FirebaseSchema.photoRef] produces, the uid is
 * resolved fresh per call through [CurrentUidProvider], and the owner-only `storage.rules`
 * gate every call. The Firebase calls live in `firebase-bridge.mjs` behind
 * [WebFirebaseStorageBridge].
 *
 * [loadPhoto] downloads [PhotoContent.Bytes], rendered by
 * `com.fluxit.ui.components.decodeImageBytes`, bounded by [PhotoPolicy.MAX_UPLOAD_BYTES]
 * like the other platforms.
 *
 * [loadPhoto] and [deletePhoto] treat Storage's "object does not exist" outcome as the
 * documented missing-object case (`null` / silent no-op); any other failure propagates as
 * [PhotoStorageException], never the raw bridge error. [uploadPhoto] swallows nothing, so
 * `replacePhoto`'s safe-replace ordering leaves the old photo untouched on failure. The
 * content type is derived from the validated bytes, since generated photo IDs have no
 * extension.
 */
class WebPhotoStorage internal constructor(
    private val bridgeProvider: () -> WebFirebaseStorageBridge,
    private val currentUid: CurrentUidProvider,
    private val photoIdFactory: () -> String,
) : PhotoStorage {

    constructor() : this({ JsWebFirebaseStorageBridge }, WebAuthBridgeCurrentUidProvider(), ::newPhotoId)

    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String {
        val photoRef = FirebaseSchema.photoRef(currentUid.currentUid(), itemId, photoIdFactory())
        val mimeType = validatePhotoSource(bytes).mimeType
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                bridgeProvider().uploadData(photoRef, bytes, mimeType) { error ->
                    if (!continuation.isActive) return@uploadData
                    if (error != null) {
                        continuation.resumeWithException(PhotoStorageWebException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: PhotoStorageWebException) {
            throw PhotoStorageException(failure.toApplicationError())
        }
        return photoRef
    }

    override suspend fun loadPhoto(photoRef: String): PhotoContent? = try {
        val bytes = suspendCancellableCoroutine<ByteArray> { continuation ->
            bridgeProvider().downloadData(photoRef, MAX_DOWNLOAD_BYTES) { data, error ->
                if (!continuation.isActive) return@downloadData
                when {
                    error != null -> continuation.resumeWithException(PhotoStorageWebException(error))
                    data != null -> continuation.resume(data)
                    else -> continuation.resumeWithException(
                        IllegalStateException("Storage download for $photoRef completed with neither data nor error"),
                    )
                }
            }
        }
        PhotoContent.Bytes(bytes)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (missing: PhotoStorageWebException) {
        if (missing.error.isStorageObjectNotFound()) null else throw PhotoStorageException(missing.toApplicationError())
    } catch (failure: IllegalStateException) {
        throw PhotoStorageException(RepositoryErrorCode.UNKNOWN.toApplicationError())
    }

    override suspend fun deletePhoto(photoRef: String) {
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                bridgeProvider().deleteObject(photoRef) { error ->
                    if (!continuation.isActive) return@deleteObject
                    if (error != null) {
                        continuation.resumeWithException(PhotoStorageWebException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (missing: PhotoStorageWebException) {
            if (!missing.error.isStorageObjectNotFound()) throw PhotoStorageException(missing.toApplicationError())
        }
    }

    private companion object {
        /** Same reuse rationale as `AndroidPhotoStorage.MAX_DOWNLOAD_BYTES`'s KDoc. */
        val MAX_DOWNLOAD_BYTES: Long = PhotoPolicy.MAX_UPLOAD_BYTES.toLong()
    }
}
