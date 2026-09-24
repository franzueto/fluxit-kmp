package com.fluxit.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.fluxit.data.toByteArray
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

actual fun decodeImageFile(path: String): ImageBitmap? = runCatching {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    Image.makeFromEncoded(data.toByteArray()).toComposeImageBitmap()
}.getOrNull()

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
