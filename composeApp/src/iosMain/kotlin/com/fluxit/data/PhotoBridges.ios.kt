@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fluxit.data

import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.IosAuthBridgeCurrentUidProvider
import com.fluxit.firebase.storage.IosFirebaseStorageBridge
import com.fluxit.firebase.storage.IosFirebaseStorageBridgeRegistry
import com.fluxit.firebase.storage.PhotoStorageIosException
import com.fluxit.firebase.storage.isStorageObjectNotFound
import com.fluxit.firebase.storage.toApplicationError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import platform.Foundation.NSProgress
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}

class IosPhotoPicker : PhotoPicker {

    // Strong reference so the delegate isn't collected while the picker is presented.
    private var activeDelegate: PHPickerViewControllerDelegateProtocol? = null

    override suspend fun pickPhoto(): ByteArray? = withContext(Dispatchers.Main) {
        var presentedPicker: PHPickerViewController? = null
        var loading: NSProgress? = null
        try { suspendCancellableCoroutine { continuation ->
            var resumed = false
            continuation.invokeOnCancellation {
                activeDelegate = null
            }
            fun finish(bytes: ByteArray?) {
                if (!resumed && continuation.isActive) {
                    resumed = true
                    activeDelegate = null
                    continuation.resume(bytes)
                }
            }

            val rootViewController = (UIApplication.sharedApplication.windows
                .firstOrNull { (it as UIWindow).isKeyWindow() } as? UIWindow)
                ?.rootViewController
                ?: (UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)?.rootViewController

            if (rootViewController == null) {
                finish(null)
                return@suspendCancellableCoroutine
            }

            val configuration = PHPickerConfiguration().apply {
                selectionLimit = 1
                filter = PHPickerFilter.imagesFilter
            }
            val picker = PHPickerViewController(configuration)
            presentedPicker = picker
            val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
                override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                    picker.dismissViewControllerAnimated(true, null)
                    val itemProvider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
                    if (itemProvider == null) {
                        finish(null)
                        return
                    }
                    loading = itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                        if (continuation.isActive) finish(data?.toByteArray())
                    }
                }
            }
            activeDelegate = delegate
            picker.delegate = delegate
            rootViewController.presentViewController(picker, animated = true, completion = null)
        } } finally {
            loading?.cancel()
            presentedPicker?.dismissViewControllerAnimated(false, null)
            activeDelegate = null
        }
    }
}

/**
 * Real Cloud Storage-backed [PhotoStorage] (`FB-305`). Every object is addressed by the exact `photoRef` string
 * [FirebaseSchema.photoRef] already produces (`users/{uid}/items/{itemId}/{photoId}`) -
 * this class never constructs or parses that shape itself, matching the contract's
 * documented boundary. The deployed owner-only `storage.rules` (`FB-005`) gate every call
 * below; this class does not, and must not, work around them.
 *
 * Uid resolution reuses [CurrentUidProvider]/[IosAuthBridgeCurrentUidProvider] exactly as
 * `IosFirebaseItemRepository` does for Firestore paths - resolved fresh per call, never
 * cached, same Phase 1 constraint. The Firebase call itself never reaches this file: per
 * PLAN-008 the `FirebaseStorage` SPM target is not cinterop-reachable from `iosMain` (see
 * `FirebaseBootstrap.swift`'s KDoc), so every actual SDK call lives in
 * `iosApp/iosApp/FirebaseStorageBridge.swift` behind [IosFirebaseStorageBridge] - mirroring
 * exactly how `IosFirebaseItemRepository`/`IosFirebaseListRepository`/`IosAuthRepository`
 * each reach their own SDK surface through a Swift-implemented Kotlin protocol.
 *
 * [loadPhoto] downloads [PhotoContent.Bytes], rendered by
 * `com.fluxit.ui.components.decodeImageBytes`. Downloads reuse
 * [PhotoPolicy.MAX_UPLOAD_BYTES] as their bound, matching upload preparation.
 *
 * ### Idempotent delete / missing-object semantics
 * [loadPhoto] and [deletePhoto] both treat Storage's own "object does not exist" outcome
 * ([com.fluxit.firebase.storage.isStorageObjectNotFound]) as the documented "missing
 * object" case ([PhotoStorage.loadPhoto] returns `null`; [PhotoStorage.deletePhoto] is a
 * silent no-op) rather than letting it escape as a thrown exception - any other failure
 * (e.g. a genuine Rules denial) still propagates, but (`FB-403`) as [PhotoStorageException]
 * (via the already-tested
 * `com.fluxit.firebase.storage.PhotoStorageIosException.toApplicationError()` mapping,
 * `FB-401`), never the raw [com.fluxit.firebase.storage.PhotoStorageIosException]/[NSError]
 * instance - discharging the remainder of `FB-305-NB2`/`FB-401-NB1`/`FB-401-NB2`. [uploadPhoto]
 * deliberately swallows nothing: a failed upload must propagate so `replacePhoto`'s safe-replace
 * ordering (`PhotoBridges.kt`, unmodified by this task) leaves the old photo untouched, per its
 * documented failure semantics - mirrors `AndroidPhotoStorage.uploadPhoto` exactly.
 * FB-602 sends MIME metadata derived from the validated bytes through the Swift bridge;
 * generated photo IDs have no extension from which the Storage SDK can infer a type.
 */
class IosPhotoStorage(
    private val bridgeProvider: () -> IosFirebaseStorageBridge = IosFirebaseStorageBridgeRegistry::requireBridge,
    private val currentUid: CurrentUidProvider = IosAuthBridgeCurrentUidProvider(),
    private val photoIdFactory: () -> String = ::newPhotoId,
) : PhotoStorage {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String {
        val photoRef = FirebaseSchema.photoRef(currentUid.currentUid(), itemId, photoIdFactory())
        val mimeType = validatePhotoSource(bytes).mimeType
        val data = bytes.toNSData()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                bridgeProvider().uploadData(photoRef, data, mimeType) { error ->
                    if (!continuation.isActive) return@uploadData
                    if (error != null) {
                        continuation.resumeWithException(PhotoStorageIosException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: PhotoStorageIosException) {
            throw PhotoStorageException(failure.toApplicationError())
        }
        return photoRef
    }

    override suspend fun loadPhoto(photoRef: String): PhotoContent? = try {
        val data = suspendCancellableCoroutine<NSData> { continuation ->
            bridgeProvider().downloadData(photoRef, MAX_DOWNLOAD_BYTES) { data, error ->
                if (!continuation.isActive) return@downloadData
                when {
                    error != null -> continuation.resumeWithException(PhotoStorageIosException(error))
                    data != null -> continuation.resume(data)
                    else -> continuation.resumeWithException(
                        IllegalStateException("Storage download for $photoRef completed with neither data nor error"),
                    )
                }
            }
        }
        PhotoContent.Bytes(data.toByteArray())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (missing: PhotoStorageIosException) {
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
                        continuation.resumeWithException(PhotoStorageIosException(error))
                    } else {
                        continuation.resume(Unit)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (missing: PhotoStorageIosException) {
            if (!missing.error.isStorageObjectNotFound()) throw PhotoStorageException(missing.toApplicationError())
        }
    }

    private companion object {
        /** Same reuse rationale as `AndroidPhotoStorage.MAX_DOWNLOAD_BYTES`'s KDoc. */
        val MAX_DOWNLOAD_BYTES: Long = PhotoPolicy.MAX_UPLOAD_BYTES.toLong()
    }
}

/**
 * Builds an [NSData] view over [this] array's bytes, the same conversion the `FB-302` local-
 * file stub used to persist bytes to disk - reused unmodified as the wire type
 * [IosFirebaseStorageBridge.uploadData] crosses to Swift. `internal` (not `private`) so
 * `IosPhotoStorageIntegrationCheck` (parity-only) can reuse it for its raw,
 * [IosPhotoStorage]-bypassing cross-user write-denial check, rather than duplicating this
 * cinterop conversion a third time.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.dataWithBytes(pinned.addressOf(0), size.toULong())
}
