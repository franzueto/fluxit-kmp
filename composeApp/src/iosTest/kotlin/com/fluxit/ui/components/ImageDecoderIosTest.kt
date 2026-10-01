package com.fluxit.ui.components

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Real Skia byte decoding used by ItemDetailScreen after a Firebase download. */
class ImageDecoderIosTest {
    @Test fun rendersDownloadedPngBytes() {
        val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        val image = assertNotNull(decodeImageBytes(png))
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }

    @Test fun rejectsCorruptAndEmptyBytes() {
        assertNull(decodeImageBytes(byteArrayOf(1, 2, 3)))
        assertNull(decodeImageBytes(ByteArray(0)))
    }
}
