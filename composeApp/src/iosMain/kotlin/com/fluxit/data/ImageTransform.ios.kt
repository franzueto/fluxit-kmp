package com.fluxit.data

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/**
 * real decode/resize actuals, backing [preparePhotoForUpload]'s default parameters on
 * iOS. Uses only `org.jetbrains.skia` (Skiko) - the same Skia bindings `ImageDecoder.ios.kt`
 * already depends on for rendering - so no new third-party dependency is introduced. Unlike the
 * Android actual, Kotlin/Native test binaries link real Skia, so these are exercised for real
 * by `ImageTransformIosTest` under `iosSimulatorArm64Test`.
 */

actual fun readImageDimensions(bytes: ByteArray): ImageDimensions? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    ImageDimensions(image.width, image.height)
}.getOrNull()

actual fun resizeImage(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray? = runCatching {
    val source = Image.makeFromEncoded(bytes)
    val longEdge = maxOf(source.width, source.height)
    val scale = if (longEdge > maxDimensionPx) maxDimensionPx.toFloat() / longEdge else 1f
    val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
    surface.canvas.drawImageRect(source, Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()))
    surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, quality)?.bytes
}.getOrNull()
