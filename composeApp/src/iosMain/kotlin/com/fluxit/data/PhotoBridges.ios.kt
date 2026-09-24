@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fluxit.data

import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.IosAuthBridgeCurrentUidProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithBytes
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

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
        suspendCancellableCoroutine { continuation ->
            var resumed = false
            fun finish(bytes: ByteArray?) {
                if (!resumed) {
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
            val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
                override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                    picker.dismissViewControllerAnimated(true, null)
                    val itemProvider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
                    if (itemProvider == null) {
                        finish(null)
                        return
                    }
                    itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                        finish(data?.toByteArray())
                    }
                }
            }
            activeDelegate = delegate
            picker.delegate = delegate
            rootViewController.presentViewController(picker, animated = true, completion = null)
        }
    }
}

/**
 * Interim local-file [PhotoStorage] stub (`FB-302`). It produces/consumes correctly-shaped
 * `photoRef` strings (PLAN-006/PLAN-007, via [FirebaseSchema.photoRef]) using the real
 * signed-in uid ([CurrentUidProvider] - the same Swift-bridge-backed seam
 * `IosFirebaseItemRepository` already uses for Firestore paths, per PLAN-008 - so a
 * `photoRef` minted here is already exactly the string a real Cloud Storage adapter would
 * need), but it still stores bytes on local disk rather than in Cloud Storage. Real
 * upload/download/delete against Firebase Storage is `FB-305`, deliberately out of this
 * task's scope.
 */
class IosPhotoStorage(
    private val baseDir: String,
    private val currentUid: CurrentUidProvider = IosAuthBridgeCurrentUidProvider(),
) : PhotoStorage {

    private val photosDir: String
        get() = "$baseDir/photos".also {
            NSFileManager.defaultManager.createDirectoryAtPath(
                it, withIntermediateDirectories = true, attributes = null, error = null,
            )
        }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String = withContext(Dispatchers.Default) {
        val photoRef = FirebaseSchema.photoRef(currentUid.currentUid(), itemId, newPhotoId())
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }
        data.writeToFile(localPath(photoRef), atomically = true)
        photoRef
    }

    override suspend fun loadPhoto(photoRef: String): PhotoContent? = withContext(Dispatchers.Default) {
        val path = localPath(photoRef)
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) PhotoContent.Loadable(path) else null
    }

    override suspend fun deletePhoto(photoRef: String) {
        withContext(Dispatchers.Default) {
            NSFileManager.defaultManager.removeItemAtPath(localPath(photoRef), error = null)
        }
    }

    /**
     * Maps a `photoRef` 1:1 onto a local cache path by flattening its path separators.
     * `FB-305` replaces this whole class with a real Cloud Storage object addressed by the
     * same `photoRef`; nothing else needs to change when it does.
     */
    private fun localPath(photoRef: String): String = "$photosDir/${photoRef.replace('/', '_')}"
}
