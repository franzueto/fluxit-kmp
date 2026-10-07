package com.fluxit.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * real decode/resize actuals, backing [preparePhotoForUpload]'s default parameters on
 * Android. Uses only `android.graphics` (already a platform dependency) - no new third-party
 * dependency. Not exercised by `testDebugUnitTest` directly (no Robolectric in this project;
 * `android.graphics.BitmapFactory` is stubbed to throw under plain JVM unit tests), so
 * correctness here relies on [readImageDimensions]/[resizeImage] being compiled and on the
 * pure decision logic in `PhotoPolicy.kt` being exhaustively tested against fakes instead -
 * see `PhotoPolicyTest` and the iOS real-decode smoke test (`ImageTransformSkikoTest`) for the
 * platform-actual coverage this pass could add safely.
 */

actual fun readImageDimensions(bytes: ByteArray): ImageDimensions? = runCatching {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) null
    else ImageDimensions(options.outWidth, options.outHeight)
}.getOrNull()

actual fun resizeImage(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray? = runCatching {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val longEdge = maxOf(source.width, source.height)
    val scale = if (longEdge > maxDimensionPx) maxDimensionPx.toFloat() / longEdge else 1f
    val scaled = if (scale < 1f) {
        val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    } else {
        source
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== source) scaled.recycle()
    source.recycle()
    out.toByteArray()
}.getOrNull()
