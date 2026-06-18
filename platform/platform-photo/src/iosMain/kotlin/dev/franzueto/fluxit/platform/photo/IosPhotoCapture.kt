package dev.franzueto.fluxit.platform.photo

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.port.CapturedPhoto
import dev.franzueto.fluxit.shared.domain.port.PhotoCapture
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * `UIImagePickerController`-backed [PhotoCapture] (plan/06 §6/§7). [capture] presents
 * the camera, [pickFromLibrary] the photo library; the frontmost view controller to
 * present from comes from the §7 [TopViewControllerProvider] host-holder. Each call
 * suspends on a continuation the picker delegate resumes with the picked image bytes,
 * a [CaptureError.UserCancelled] on dismiss, or [CaptureError.Unknown] when no host
 * is available.
 *
 * **Divergence (plan §6):** v1 uses `UIImagePickerController` for *both* sources —
 * one delegate, no extra `PHPickerViewController` plumbing. PHPicker (no library
 * permission prompt) is a documented follow-up. Re-encoding happens above this port.
 *
 * **Manual-QA only** (plan/06 scope): built (not run) by the iOS-Sim gate.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosPhotoCapture(
    private val topViewControllerProvider: TopViewControllerProvider = DefaultTopViewControllerProvider,
) : PhotoCapture {
    // Held during presentation so the delegate isn't collected mid-flight.
    private var activeDelegate: PickerDelegate? = null

    override suspend fun capture(): Outcome<CapturedPhoto, CaptureError> =
        present(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)

    override suspend fun pickFromLibrary(): Outcome<CapturedPhoto, CaptureError> =
        present(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary)

    private suspend fun present(sourceType: UIImagePickerControllerSourceType): Outcome<CapturedPhoto, CaptureError> {
        val host = topViewControllerProvider.topViewController() ?: return Outcome.Err(CaptureError.Unknown(null))
        // Assigning an unavailable source type to UIImagePickerController throws
        // ("Source type N not available") — most commonly the camera on the Simulator,
        // which has no hardware. Guard so it surfaces as a recoverable error, not a crash.
        if (!UIImagePickerController.isSourceTypeAvailable(sourceType)) {
            return Outcome.Err(CaptureError.Unknown(IllegalStateException("Image source $sourceType not available on this device")))
        }
        return suspendCancellableCoroutine { cont ->
            val picker = UIImagePickerController()
            picker.sourceType = sourceType
            val delegate =
                PickerDelegate { result ->
                    activeDelegate = null
                    picker.dismissViewControllerAnimated(true, completion = null)
                    cont.resume(result)
                }
            activeDelegate = delegate
            picker.delegate = delegate
            cont.invokeOnCancellation {
                activeDelegate = null
                picker.dismissViewControllerAnimated(true, completion = null)
            }
            host.presentViewController(picker, animated = true, completion = null)
        }
    }
}

/** Bridges the `UIImagePickerController` delegate callbacks to a single [onResult]. */
@OptIn(ExperimentalForeignApi::class)
private class PickerDelegate(
    private val onResult: (Outcome<CapturedPhoto, CaptureError>) -> Unit,
) : NSObject(),
    UIImagePickerControllerDelegateProtocol,
    UINavigationControllerDelegateProtocol {
    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        onResult(image?.toCapturedPhoto() ?: Outcome.Err(CaptureError.Unknown(null)))
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        onResult(Outcome.Err(CaptureError.UserCancelled))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.toCapturedPhoto(): Outcome<CapturedPhoto, CaptureError> {
    val data: NSData = UIImageJPEGRepresentation(this, 1.0) ?: return Outcome.Err(CaptureError.Unknown(null))
    val (w, h) = size.useContents { width to height }
    return Outcome.Ok(
        CapturedPhoto(
            bytes = data.toByteArray(),
            mime = "image/jpeg",
            widthPx = w.toInt(),
            heightPx = h.toInt(),
        ),
    )
}

/** Walks the key window's presented-controller chain to the frontmost controller. */
private val DefaultTopViewControllerProvider =
    TopViewControllerProvider {
        var top: UIViewController? =
            UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) {
            top = top.presentedViewController
        }
        top
    }
