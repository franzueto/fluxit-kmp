package com.fluxit

import com.fluxit.data.ImageDimensions
import com.fluxit.data.ImageSniffer
import com.fluxit.data.ImageType
import com.fluxit.data.PhotoPolicy
import com.fluxit.data.PhotoRejected
import com.fluxit.data.SniffResult
import com.fluxit.data.preparePhotoForUpload
import com.fluxit.data.validatePhotoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val WEBP_HEADER =
    byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50) // "RIFF????WEBP"
private val GIF_MAGIC = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) // "GIF89a"
private val BMP_MAGIC = byteArrayOf(0x42, 0x4D)

/** A minimal, valid-looking JPEG-signature byte array of exactly [size] bytes. */
private fun fakeJpegOfSize(size: Int): ByteArray =
    JPEG_MAGIC + ByteArray(size - JPEG_MAGIC.size)

/**
 * Pure unit tests for the image validation/resize-decision policy in `PhotoPolicy.kt`
 * and `ImageTransform.kt`'s expect declarations, exercised entirely with fake/in-memory byte
 * arrays and injected fake decode/resize lambdas - no platform code, no real image decoding.
 * Matches the ledger's required acceptance evidence directly: boundary, unsupported-type, and
 * corrupt-image tests.
 */
class ImageSnifferTest {

    @Test
    fun recognizesJpegPngAndWebp() {
        assertEquals(SniffResult.Recognized(ImageType.JPEG), ImageSniffer.detect(JPEG_MAGIC + byteArrayOf(1, 2, 3)))
        assertEquals(SniffResult.Recognized(ImageType.PNG), ImageSniffer.detect(PNG_MAGIC + byteArrayOf(1, 2, 3)))
        assertEquals(SniffResult.Recognized(ImageType.WEBP), ImageSniffer.detect(WEBP_HEADER))
    }

    @Test
    fun recognizesGifAndBmpAsUnsupportedNotCorrupt() {
        assertEquals(SniffResult.UnsupportedSignature("gif"), ImageSniffer.detect(GIF_MAGIC + byteArrayOf(1)))
        assertEquals(SniffResult.UnsupportedSignature("bmp"), ImageSniffer.detect(BMP_MAGIC + byteArrayOf(1)))
    }

    @Test
    fun emptyAndRandomBytesAreUnrecognized() {
        assertEquals(SniffResult.Unrecognized, ImageSniffer.detect(ByteArray(0)))
        assertEquals(SniffResult.Unrecognized, ImageSniffer.detect(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun tooShortToMatchAnySignatureIsUnrecognized() {
        // One byte - shorter than every known magic number, must not throw or false-match.
        assertEquals(SniffResult.Unrecognized, ImageSniffer.detect(byteArrayOf(0xFF.toByte())))
    }
}

class ValidatePhotoSourceTest {

    @Test
    fun acceptsASupportedTypeUnderTheSizeLimit() {
        assertEquals(ImageType.JPEG, validatePhotoSource(fakeJpegOfSize(1024)))
    }

    @Test
    fun boundary_exactlyAtMaxSourceBytesIsAccepted() {
        assertEquals(ImageType.JPEG, validatePhotoSource(fakeJpegOfSize(PhotoPolicy.MAX_SOURCE_BYTES)))
    }

    @Test
    fun boundary_oneByteOverMaxSourceBytesIsRejected() {
        val error = assertFailsWith<PhotoRejected.TooLarge> {
            validatePhotoSource(fakeJpegOfSize(PhotoPolicy.MAX_SOURCE_BYTES + 1))
        }
        assertEquals(PhotoPolicy.MAX_SOURCE_BYTES + 1, error.actualBytes)
        assertEquals(PhotoPolicy.MAX_SOURCE_BYTES, error.maxBytes)
    }

    @Test
    fun boundary_oneByteUnderMaxSourceBytesIsAccepted() {
        assertEquals(ImageType.JPEG, validatePhotoSource(fakeJpegOfSize(PhotoPolicy.MAX_SOURCE_BYTES - 1)))
    }

    @Test
    fun unsupportedButRecognizedTypeIsRejectedAsUnsupportedNotCorrupt() {
        val error = assertFailsWith<PhotoRejected.UnsupportedType> {
            validatePhotoSource(GIF_MAGIC + byteArrayOf(1, 2, 3))
        }
        assertEquals("gif", error.detected)
    }

    @Test
    fun bmpIsRejectedAsUnsupported() {
        assertFailsWith<PhotoRejected.UnsupportedType> { validatePhotoSource(BMP_MAGIC + byteArrayOf(1)) }
    }

    @Test
    fun unrecognizedBytesAreRejectedAsCorrupt() {
        assertFailsWith<PhotoRejected.Corrupt> { validatePhotoSource(byteArrayOf(1, 2, 3, 4)) }
    }

    @Test
    fun emptyBytesAreRejectedAsCorrupt() {
        assertFailsWith<PhotoRejected.Corrupt> { validatePhotoSource(ByteArray(0)) }
    }

    @Test
    fun sizeIsEnforcedBeforeTypeSoAnOversizedUnrecognizedFileReportsTooLargeNotCorrupt() {
        // An oversized file that isn't even a recognizable image should still be reported as
        // too-large (the cheaper, pre-decode check) rather than corrupt.
        val error = assertFailsWith<PhotoRejected.TooLarge> {
            validatePhotoSource(ByteArray(PhotoPolicy.MAX_SOURCE_BYTES + 1))
        }
        assertEquals(PhotoPolicy.MAX_SOURCE_BYTES + 1, error.actualBytes)
    }
}

class PreparePhotoForUploadTest {

    private val smallDimensions = ImageDimensions(800, 600)
    private val hugeDimensions = ImageDimensions(6000, 4000)

    @Test
    fun aSmallAlreadyCompliantImageIsUploadedUnchangedAndNeverResized() {
        var resizeCalled = false
        val bytes = fakeJpegOfSize(1024)

        val result = preparePhotoForUpload(
            bytes,
            readDimensions = { smallDimensions },
            resize = { _, _, _ -> resizeCalled = true; ByteArray(0) },
        )

        assertEquals(bytes.toList(), result.toList())
        assertFalse(resizeCalled, "an already-small image must not be re-encoded")
    }

    @Test
    fun anOversizedDimensionTriggersResize() {
        val resizedBytes = fakeJpegOfSize(2048)
        var capturedMaxDimension: Int? = null
        var capturedQuality: Int? = null

        val result = preparePhotoForUpload(
            fakeJpegOfSize(1024),
            readDimensions = { hugeDimensions },
            resize = { _, maxDim, quality -> capturedMaxDimension = maxDim; capturedQuality = quality; resizedBytes },
        )

        assertEquals(resizedBytes.toList(), result.toList())
        assertEquals(PhotoPolicy.MAX_DIMENSION_PX, capturedMaxDimension)
        assertEquals(PhotoPolicy.JPEG_QUALITY, capturedQuality)
    }

    @Test
    fun boundary_dimensionExactlyAtLimitIsNotResized() {
        var resizeCalled = false
        val dims = ImageDimensions(PhotoPolicy.MAX_DIMENSION_PX, PhotoPolicy.MAX_DIMENSION_PX)

        preparePhotoForUpload(
            fakeJpegOfSize(1024),
            readDimensions = { dims },
            resize = { _, _, _ -> resizeCalled = true; null },
        )

        assertFalse(resizeCalled)
    }

    @Test
    fun boundary_dimensionOnePixelOverLimitIsResized() {
        var resizeCalled = false
        val dims = ImageDimensions(PhotoPolicy.MAX_DIMENSION_PX + 1, 100)

        preparePhotoForUpload(
            fakeJpegOfSize(1024),
            readDimensions = { dims },
            resize = { _, _, _ -> resizeCalled = true; byteArrayOf(1) },
        )

        assertTrue(resizeCalled)
    }

    @Test
    fun aByteSizeOverTheUploadLimitTriggersResizeEvenWithSmallDimensions() {
        var resizeCalled = false
        val bytes = fakeJpegOfSize(PhotoPolicy.MAX_UPLOAD_BYTES + 1)

        preparePhotoForUpload(
            bytes,
            readDimensions = { smallDimensions },
            resize = { _, _, _ -> resizeCalled = true; byteArrayOf(1) },
        )

        assertTrue(resizeCalled, "a small-dimension but oversized-bytes image must still be recompressed")
    }

    @Test
    fun aSourceThatFailsValidationNeverReachesDimensionReadingOrResize() {
        var readCalled = false
        var resizeCalled = false

        assertFailsWith<PhotoRejected.Corrupt> {
            preparePhotoForUpload(
                byteArrayOf(1, 2, 3),
                readDimensions = { readCalled = true; smallDimensions },
                resize = { _, _, _ -> resizeCalled = true; null },
            )
        }

        assertFalse(readCalled)
        assertFalse(resizeCalled)
    }

    @Test
    fun unsupportedTypeIsRejectedBeforeAnyDecodeAttempt() {
        var readCalled = false
        assertFailsWith<PhotoRejected.UnsupportedType> {
            preparePhotoForUpload(
                GIF_MAGIC + byteArrayOf(1, 2, 3),
                readDimensions = { readCalled = true; smallDimensions },
            )
        }
        assertFalse(readCalled)
    }

    @Test
    fun undecodableBytesThatPassedSniffingAreReportedAsCorrupt() {
        // Passes the magic-byte sniff but the injected "decoder" fails - a truncated/malformed
        // file with a valid-looking header.
        assertFailsWith<PhotoRejected.Corrupt> {
            preparePhotoForUpload(fakeJpegOfSize(1024), readDimensions = { null })
        }
    }

    @Test
    fun aResizeThatFailsToDecodeIsReportedAsCorrupt() {
        assertFailsWith<PhotoRejected.Corrupt> {
            preparePhotoForUpload(
                fakeJpegOfSize(1024),
                readDimensions = { hugeDimensions },
                resize = { _, _, _ -> null },
            )
        }
    }

    @Test
    fun boundary_resizedOutputExactlyAtUploadLimitIsAccepted() {
        val exact = fakeJpegOfSize(PhotoPolicy.MAX_UPLOAD_BYTES)
        val result = preparePhotoForUpload(
            fakeJpegOfSize(1024),
            readDimensions = { hugeDimensions },
            resize = { _, _, _ -> exact },
        )
        assertEquals(PhotoPolicy.MAX_UPLOAD_BYTES, result.size)
    }

    @Test
    fun boundary_resizedOutputOneByteOverUploadLimitIsRejected() {
        val overLimit = fakeJpegOfSize(PhotoPolicy.MAX_UPLOAD_BYTES + 1)
        val error = assertFailsWith<PhotoRejected.TooLarge> {
            preparePhotoForUpload(
                fakeJpegOfSize(1024),
                readDimensions = { hugeDimensions },
                resize = { _, _, _ -> overLimit },
            )
        }
        assertEquals(PhotoPolicy.MAX_UPLOAD_BYTES + 1, error.actualBytes)
        assertEquals(PhotoPolicy.MAX_UPLOAD_BYTES, error.maxBytes)
    }
}
