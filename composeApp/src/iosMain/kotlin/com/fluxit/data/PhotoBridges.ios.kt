@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fluxit.data

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

class IosPhotoStorage(private val baseDir: String) : PhotoStorage {

    private val photosDir: String
        get() = "$baseDir/photos".also {
            NSFileManager.defaultManager.createDirectoryAtPath(
                it, withIntermediateDirectories = true, attributes = null, error = null,
            )
        }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun savePhoto(bytes: ByteArray): String = withContext(Dispatchers.Default) {
        val path = "$photosDir/${newId()}.jpg"
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }
        data.writeToFile(path, atomically = true)
        path
    }

    override suspend fun deletePhoto(path: String) {
        withContext(Dispatchers.Default) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }
}
