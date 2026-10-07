package com.fluxit.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boundary-value tests for the `BitmapFactory`-backed actuals in `ImageTransform.android.kt`
 * and for [preparePhotoForUpload] running on them. `testDebugUnitTest` cannot cover these
 * (no Robolectric; `BitmapFactory` is stubbed there), so this runs on a real Android runtime.
 * iOS has the equivalent real-decode coverage in `ImageTransformIosTest`.
 *
 * Needs no emulator suite: nothing here touches Firebase.
 */
@RunWith(AndroidJUnit4::class)
class ImageTransformInstrumentedTest {

    // --- readImageDimensions / resizeImage -----------------------------------------------

    @Test
    fun readImageDimensions_realPngJpegAndWebp_returnTheirPixelSize() {
        assertEquals(ImageDimensions(7, 3), readImageDimensions(encode(7, 3, Bitmap.CompressFormat.PNG)))
        assertEquals(ImageDimensions(7, 3), readImageDimensions(encode(7, 3, Bitmap.CompressFormat.JPEG)))
        assertEquals(ImageDimensions(7, 3), readImageDimensions(encode(7, 3, WEBP)))
    }

    @Test
    fun readImageDimensions_corruptAndEmptyBytes_returnNull() {
        assertNull(readImageDimensions(ByteArray(0)))
        assertNull(readImageDimensions(byteArrayOf(1, 2, 3)))
        // A valid PNG signature with no decodable image behind it.
        assertNull(readImageDimensions(PNG_SIGNATURE + ByteArray(32)))
    }

    @Test
    fun resizeImage_longEdgeAboveTheBound_scalesDownKeepingAspectRatio() {
        val resized = assertNotNull(resizeImage(encode(400, 200, Bitmap.CompressFormat.PNG), 100, 80))

        assertEquals(ImageDimensions(100, 50), readImageDimensions(resized))
        assertEquals(ImageType.JPEG, typeOf(resized))
    }

    @Test
    fun resizeImage_imageAlreadyWithinTheBound_isNotUpscaled() {
        val resized = assertNotNull(resizeImage(encode(40, 20, Bitmap.CompressFormat.PNG), 100, 80))

        assertEquals(ImageDimensions(40, 20), readImageDimensions(resized))
    }

    @Test
    fun resizeImage_corruptBytes_returnNull() {
        assertNull(resizeImage(byteArrayOf(1, 2, 3), 128, 80))
        assertNull(resizeImage(PNG_SIGNATURE + ByteArray(32), 128, 80))
    }

    // --- preparePhotoForUpload with the real actuals -------------------------------------

    @Test
    fun prepare_longEdgeExactlyAtTheLimit_isUploadedByteForByte() {
        val source = encode(PhotoPolicy.MAX_DIMENSION_PX, 1, Bitmap.CompressFormat.PNG)

        assertContentEquals(source, preparePhotoForUpload(source))
    }

    @Test
    fun prepare_longEdgeOnePixelOverTheLimit_isResizedToTheLimit() {
        val source = encode(PhotoPolicy.MAX_DIMENSION_PX + 1, 1, Bitmap.CompressFormat.PNG)

        val prepared = preparePhotoForUpload(source)

        assertEquals(PhotoPolicy.MAX_DIMENSION_PX, readImageDimensions(prepared)?.widthPx)
        assertEquals(ImageType.JPEG, typeOf(prepared))
    }

    @Test
    fun prepare_sizeExactlyAtTheUploadLimit_isUploadedByteForByte() {
        val source = padded(encode(4, 4, Bitmap.CompressFormat.PNG), PhotoPolicy.MAX_UPLOAD_BYTES)

        assertContentEquals(source, preparePhotoForUpload(source))
    }

    @Test
    fun prepare_sizeOneByteOverTheUploadLimit_isRecompressedUnderIt() {
        val source = padded(encode(4, 4, Bitmap.CompressFormat.PNG), PhotoPolicy.MAX_UPLOAD_BYTES + 1)

        val prepared = preparePhotoForUpload(source)

        assertTrue(prepared.size <= PhotoPolicy.MAX_UPLOAD_BYTES, "size=${prepared.size}")
        assertEquals(ImageDimensions(4, 4), readImageDimensions(prepared))
    }

    @Test
    fun prepare_sourceExactlyAtTheSourceLimit_isAcceptedAndOneByteOverIsRejected() {
        val atLimit = padded(encode(4, 4, Bitmap.CompressFormat.PNG), PhotoPolicy.MAX_SOURCE_BYTES)
        assertTrue(preparePhotoForUpload(atLimit).size <= PhotoPolicy.MAX_UPLOAD_BYTES)

        val overLimit = padded(atLimit, PhotoPolicy.MAX_SOURCE_BYTES + 1)
        assertFailsWith<PhotoRejected.TooLarge> { preparePhotoForUpload(overLimit) }
    }

    @Test
    fun prepare_unsupportedType_isRejected() {
        assertFailsWith<PhotoRejected.UnsupportedType> {
            preparePhotoForUpload("GIF89a".encodeToByteArray() + ByteArray(32))
        }
        assertFailsWith<PhotoRejected.UnsupportedType> {
            preparePhotoForUpload("BM".encodeToByteArray() + ByteArray(32))
        }
    }

    @Test
    fun prepare_corruptImages_areRejected() {
        assertFailsWith<PhotoRejected.Corrupt> { preparePhotoForUpload(ByteArray(0)) }
        assertFailsWith<PhotoRejected.Corrupt> { preparePhotoForUpload(byteArrayOf(1, 2, 3)) }
        // Passes the signature sniff, fails the real decode.
        assertFailsWith<PhotoRejected.Corrupt> { preparePhotoForUpload(PNG_SIGNATURE + ByteArray(32)) }
    }

    // --- helpers -------------------------------------------------------------------------

    private fun typeOf(bytes: ByteArray): ImageType? = (ImageSniffer.detect(bytes) as? SniffResult.Recognized)?.type

    private fun encode(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 80, 40))
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, 90, out)) { "could not encode a ${width}x$height $format test image" }
        bitmap.recycle()
        return out.toByteArray()
    }

    /** [bytes] followed by zero padding up to exactly [size] bytes; decoders ignore trailing data. */
    private fun padded(bytes: ByteArray, size: Int): ByteArray {
        require(size >= bytes.size)
        return bytes.copyOf(size)
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        @Suppress("DEPRECATION")
        val WEBP: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP
    }
}
