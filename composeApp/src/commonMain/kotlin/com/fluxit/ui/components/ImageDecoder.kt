package com.fluxit.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an image file from app-internal storage. Returns null if the file is missing/corrupt. */
expect fun decodeImageFile(path: String): ImageBitmap?

/** Decodes an already-in-memory image (e.g. a [com.fluxit.data.PhotoContent.Bytes] payload). Returns null if corrupt. */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
