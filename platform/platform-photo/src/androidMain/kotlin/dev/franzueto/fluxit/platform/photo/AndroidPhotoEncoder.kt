package dev.franzueto.fluxit.platform.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * BitmapFactory-backed [PhotoEncoder] (plan/06 §6). Decodes with an `inSampleSize`
 * that gets the bitmap close to [maxDim] without loading the full-resolution image
 * (memory-safe for large captures), then scales exactly to the bound and re-encodes
 * as JPEG. Runs on `Dispatchers.IO`.
 */
public class AndroidPhotoEncoder : PhotoEncoder {
    override suspend fun reencode(
        bytes: ByteArray,
        mime: String,
        maxDim: Int,
        jpegQuality: Float,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            // Pass 1: read bounds only so we can pick an inSampleSize for the decode.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val srcLongest = max(bounds.outWidth, bounds.outHeight)

            val decodeOpts =
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(srcLongest, maxDim)
                }
            val decoded =
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    ?: return@withContext bytes // undecodable → pass the original through

            val scaled = scaleToBound(decoded, maxDim)
            val quality = (jpegQuality * 100).toInt().coerceIn(1, 100)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            out.toByteArray()
        }

    /** Largest power-of-two sample size that keeps the sampled longest edge ≥ [maxDim]. */
    private fun sampleSizeFor(
        srcLongest: Int,
        maxDim: Int,
    ): Int {
        if (srcLongest <= 0 || maxDim <= 0) return 1
        var sample = 1
        while (srcLongest / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private fun scaleToBound(
        bitmap: Bitmap,
        maxDim: Int,
    ): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
