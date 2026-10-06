package com.fluxit.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.*

class BoundedPhotoDownloadTest {
    private class Source(private val size: Int, private val fail: Boolean = false) : InputStream() {
        var consumed = 0
        var closed = false
        override fun read(): Int = error("Bulk reads required")
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (fail) throw IOException("Disconnected/canceled stream")
            if (consumed == size) return -1
            val count = minOf(length, size - consumed)
            bytes.fill(7, offset, offset + count)
            consumed += count
            return count
        }
        override fun close() { closed = true }
    }
    @Test fun bytesRoundTripAndStreamCloses() {
        val bytes = byteArrayOf(1, 2, 3)
        assertContentEquals(bytes, readBoundedPhotoBytes(ByteArrayInputStream(bytes), PhotoPolicy.MAX_UPLOAD_BYTES.toLong()))
        val source = Source(3)
        readBoundedPhotoBytes(source, 3)
        assertTrue(source.closed)
    }
    @Test fun exactExistingFiveMiBLimitIsAccepted() {
        val source = Source(PhotoPolicy.MAX_UPLOAD_BYTES)
        assertEquals(PhotoPolicy.MAX_UPLOAD_BYTES, readBoundedPhotoBytes(source, PhotoPolicy.MAX_UPLOAD_BYTES.toLong()).size)
        assertTrue(source.closed)
    }
    @Test fun oversizedInputReadsOnlyLimitPlusOneThenCloses() {
        val source = Source(Int.MAX_VALUE)
        assertFailsWith<IOException> { readBoundedPhotoBytes(source, PhotoPolicy.MAX_UPLOAD_BYTES.toLong()) }
        assertEquals(PhotoPolicy.MAX_UPLOAD_BYTES + 1, source.consumed)
        assertTrue(source.closed)
    }
    @Test fun failedOrCanceledReadClosesTheStream() {
        val source = Source(100, fail = true)
        assertFailsWith<IOException> { readBoundedPhotoBytes(source, PhotoPolicy.MAX_UPLOAD_BYTES.toLong()) }
        assertTrue(source.closed)
    }
}
