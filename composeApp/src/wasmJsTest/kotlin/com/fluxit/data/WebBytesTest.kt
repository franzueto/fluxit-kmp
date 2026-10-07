package com.fluxit.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array

/** Byte payloads survive the Kotlin ⇄ JS crossing unchanged, including every byte value. */
class WebBytesTest {

    private val everyByte = ByteArray(256) { it.toByte() }

    @Test
    fun aByteArrayRoundTripsThroughJs() {
        val js = everyByte.toUint8Array()

        assertEquals(256, js.length)
        assertContentEquals(everyByte, js.buffer.toByteArray())
    }

    @Test
    fun theCopyIsIndependentOfLaterWasmMemoryUse() {
        val first = ByteArray(1024) { 7 }.toUint8Array()
        ByteArray(4096) { 9 }.toUint8Array() // reuses the scoped staging memory

        assertContentEquals(ByteArray(1024) { 7 }, first.buffer.toByteArray())
    }

    @Test
    fun aMultiMegabytePayloadRoundTrips() {
        val large = ByteArray(5 * 1024 * 1024) { (it * 31).toByte() }

        assertContentEquals(large, large.toUint8Array().buffer.toByteArray())
    }

    @Test
    fun emptyPayloadsStayEmpty() {
        assertEquals(0, ByteArray(0).toUint8Array().length)
        assertEquals(0, ArrayBuffer(0).toByteArray().size)
        assertEquals(0, Uint8Array(0).buffer.toByteArray().size)
    }
}
