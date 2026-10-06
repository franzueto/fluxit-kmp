package com.fluxit.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an already-in-memory image (e.g. a [com.fluxit.data.PhotoContent.Bytes] payload). Returns null if corrupt. */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
