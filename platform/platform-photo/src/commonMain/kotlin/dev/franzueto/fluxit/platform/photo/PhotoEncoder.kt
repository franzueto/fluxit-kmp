package dev.franzueto.fluxit.platform.photo

/**
 * Per-platform actuals: `AndroidPhotoEncoder` (BitmapFactory + `Bitmap.compress`)
 * and `IosPhotoEncoder` (`UIImage` resize → `jpegData`).
 */
public interface PhotoEncoder {
    /**
     * Decodes [bytes] (of type [mime]), downsamples so the longest edge is at most
     * [maxDim] px, and re-encodes as JPEG at [jpegQuality] (0.0–1.0). Returns the
     * encoded JPEG bytes. Runs off the main thread.
     */
    public suspend fun reencode(
        bytes: ByteArray,
        mime: String,
        maxDim: Int,
        jpegQuality: Float,
    ): ByteArray
}

/**
 * Maps a MIME type to the file extension [PhotoStorage][dev.franzueto.fluxit.shared.domain.port.PhotoStorage]
 * writes. The encoder normalises everything to JPEG, so non-image / unknown
 * types fall back to `jpg` — storage never sees an extension-less path.
 */
public fun mimeToExtension(mime: String): String =
    when (mime.substringBefore(';').trim().lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "image/gif" -> "gif"
        else -> "jpg"
    }
