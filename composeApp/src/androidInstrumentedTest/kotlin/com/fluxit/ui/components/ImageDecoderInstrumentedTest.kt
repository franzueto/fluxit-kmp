package com.fluxit.ui.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fluxit.parity.parityPhotoBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The same byte decoder used by ItemDetailScreen after a Firebase download. */
@RunWith(AndroidJUnit4::class)
class ImageDecoderInstrumentedTest {
    @Test fun rendersDownloadedPngBytes() {
        val image = assertNotNull(decodeImageBytes(parityPhotoBytes))
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }

    @Test fun rejectsCorruptAndEmptyBytes() {
        assertNull(decodeImageBytes(byteArrayOf(1, 2, 3)))
        assertNull(decodeImageBytes(ByteArray(0)))
    }
}
