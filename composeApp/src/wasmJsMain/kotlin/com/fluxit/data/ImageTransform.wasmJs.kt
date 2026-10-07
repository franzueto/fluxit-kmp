package com.fluxit.data

// Phase 0 stubs: photo preparation is unavailable until Phase 4 moves the Skia-backed
// iOS actuals into a skikoMain source set shared with web.

actual fun readImageDimensions(bytes: ByteArray): ImageDimensions? = null

actual fun resizeImage(bytes: ByteArray, maxDimensionPx: Int, quality: Int): ByteArray? = null
