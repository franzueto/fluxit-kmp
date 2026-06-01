package dev.franzueto.fluxit.platform.photo

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.max

/**
 * `UIImage`-backed [PhotoEncoder] (plan/06 §6). Decodes the bytes, draws into a
 * downscaled bitmap context so the longest edge is at most `maxDim`, then encodes
 * with `UIImageJPEGRepresentation`. Runs off the main thread.
 *
 * Built (not run) by the iOS-Sim gate; verified by manual device QA.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosPhotoEncoder : PhotoEncoder {
    override suspend fun reencode(
        bytes: ByteArray,
        mime: String,
        maxDim: Int,
        jpegQuality: Float,
    ): ByteArray =
        withContext(Dispatchers.Default) {
            val image = UIImage(data = bytes.toNSData()) ?: return@withContext bytes
            val resized = image.scaledToMaxDimension(maxDim) ?: image
            val jpeg = UIImageJPEGRepresentation(resized, jpegQuality.toDouble())
            jpeg?.toByteArray() ?: bytes
        }

    private fun UIImage.scaledToMaxDimension(maxDim: Int): UIImage? {
        val (w, h) =
            size.useContents { width to height }
        val longest = max(w, h)
        if (longest <= maxDim || longest <= 0.0) return this
        val ratio = maxDim / longest
        val newW = w * ratio
        val newH = h * ratio
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(newW, newH), false, 1.0)
        drawInRect(CGRectMake(0.0, 0.0, newW, newH))
        val scaled = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return scaled
    }
}
