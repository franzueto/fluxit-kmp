@file:OptIn(UnsafeWasmMemoryApi::class)

package com.fluxit.data

import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array

/**
 * Byte payloads between Kotlin and JS (photo uploads, downloads and picked files), the web
 * counterpart of the iOS `NSData` conversions. A Kotlin `ByteArray` is a Wasm GC array the
 * JS side cannot read, so bytes are staged in Wasm linear memory and copied there in one
 * typed-array `set`, instead of one JS call per byte.
 */
internal fun ArrayBuffer.toByteArray(): ByteArray {
    val size = byteLength
    if (size == 0) return ByteArray(0)
    return withScopedMemoryAllocator { allocator ->
        val staging = allocator.allocate(size)
        copyArrayBufferIntoWasmMemory(this, staging.address.toInt())
        ByteArray(size) { index -> (staging + index).loadByte() }
    }
}

/** A JS `Uint8Array` holding a copy of [this]; see [toByteArray]. */
internal fun ByteArray.toUint8Array(): Uint8Array {
    if (isEmpty()) return Uint8Array(0)
    return withScopedMemoryAllocator { allocator ->
        val staging = allocator.allocate(size)
        for (index in indices) (staging + index).storeByte(this[index])
        copyWasmMemoryToUint8Array(staging.address.toInt(), size)
    }
}

// `wasmExports` is in scope in the generated glue code. The buffer is read inside each call
// because growing the memory detaches earlier views.
@JsFun("(source, address) => { new Uint8Array(wasmExports.memory.buffer, address, source.byteLength).set(new Uint8Array(source)); }")
private external fun copyArrayBufferIntoWasmMemory(source: ArrayBuffer, address: Int)

@JsFun("(address, size) => new Uint8Array(wasmExports.memory.buffer, address, size).slice()")
private external fun copyWasmMemoryToUint8Array(address: Int, size: Int): Uint8Array
