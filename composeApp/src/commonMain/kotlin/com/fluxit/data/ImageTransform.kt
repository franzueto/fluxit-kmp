package com.fluxit.data

/** Pixel dimensions of a decoded image, as reported by [readImageDimensions]/[resizeImage]. */
data class ImageDimensions(val widthPx: Int, val heightPx: Int)

/**
 * Real per-platform image decode/resize, backing [preparePhotoForUpload]'s default parameters
 * (see `PhotoPolicy.kt`). Uses the same expect/actual pattern as
 * `com.fluxit.ui.components.decodeImageBytes`: platform-native decode only -
 * `android.graphics.BitmapFactory`/`Bitmap` on Android, the already-used `org.jetbrains.skia`
 * Skia bindings on iOS (the same library `ImageDecoder.ios.kt` already depends on for
 * rendering) - so no new third-party dependency is introduced by either actual.
 */

/**
 * Reads [bytes]' pixel dimensions, using a bounds-only decode where the platform supports one
 * (so this never needs to allocate full pixel memory just to answer "how big is this"), or
 * `null` if [bytes] cannot be decoded as an image at all (used to detect a corrupt/truncated
 * file that nonetheless matched a known magic-byte signature).
 */
expect fun readImageDimensions(bytes: ByteArray): ImageDimensions?

/**
 * Decodes [bytes], scales so neither dimension exceeds [maxDimensionPx] (preserving aspect
 * ratio; an image already within the bound on both dimensions is not upscaled), and re-encodes
 * the result as JPEG at [quality] (0-100, JPEG-compressor "quality" scale). Returns `null` if
 * [bytes] cannot be decoded as an image at all.
 */
expect fun resizeImage(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray?
