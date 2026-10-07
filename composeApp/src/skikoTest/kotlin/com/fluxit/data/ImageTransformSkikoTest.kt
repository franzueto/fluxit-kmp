package com.fluxit.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Exercises the real Skia-backed [readImageDimensions]/[resizeImage] actuals - not
 * just the pure decision logic covered by `PhotoPolicyTest` in `commonTest`, which injects
 * fakes for these exact functions. The iOS and web test binaries link real Skia (the same library
 * `ImageDecoder.skiko.kt` already depends on for rendering), so this is a genuine end-to-end
 * decode/resize, unlike Android's `testDebugUnitTest` (no Robolectric in this project;
 * `android.graphics.BitmapFactory` is stubbed to throw under plain JVM unit tests - see the
 * KDoc on `ImageTransform.android.kt`).
 */
@OptIn(ExperimentalEncodingApi::class)
class ImageTransformSkikoTest {

    // A minimal, valid, hand-verifiable 1x1 transparent PNG.
    private val onePixelPng = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )

    @Test
    fun readImageDimensions_realDecode_returnsActualPixelSize() {
        assertEquals(ImageDimensions(1, 1), readImageDimensions(onePixelPng))
    }

    @Test
    fun readImageDimensions_corruptBytes_returnsNull() {
        assertNull(readImageDimensions(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun readImageDimensions_emptyBytes_returnsNull() {
        assertNull(readImageDimensions(ByteArray(0)))
    }

    @Test
    fun resizeImage_realResize_producesADecodableJpegWithinTheRequestedBound() {
        val resized = resizeImage(onePixelPng, maxDimensionPx = 1, quality = 80)

        assertNotNull(resized)
        val redecoded = readImageDimensions(resized)
        assertEquals(ImageDimensions(1, 1), redecoded)
    }

    @Test
    fun resizeImage_corruptBytes_returnsNull() {
        assertNull(resizeImage(byteArrayOf(1, 2, 3), maxDimensionPx = 128, quality = 80))
    }
}
