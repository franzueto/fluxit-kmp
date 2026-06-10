package com.fluxit.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an image file from app-internal storage. Returns null if the file is missing/corrupt. */
expect fun decodeImageFile(path: String): ImageBitmap?
