package com.fluxit.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Keeps the previous getBytes ceiling while exposing the cancellable SDK stream task. */
internal fun readBoundedPhotoBytes(stream: InputStream, limit: Long): ByteArray = stream.use {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16_384)
    var total = 0L
    while (true) {
        val count = it.read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
        if (count == -1) break
        total += count
        if (total > limit) throw IOException("Photo exceeds download size limit")
        output.write(buffer, 0, count)
    }
    output.toByteArray()
}
