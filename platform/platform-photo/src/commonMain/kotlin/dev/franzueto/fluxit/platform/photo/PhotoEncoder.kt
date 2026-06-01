package dev.franzueto.fluxit.platform.photo

/**
 * Re-encodes captured photo bytes to a bounded JPEG before the repository
 * ingests them (plan/06 §6; Phase 03 open question — re-encode at ingest).
 * Kept out of [PhotoCapture][dev.franzueto.fluxit.shared.domain.port.PhotoCapture]
 * so the capture impl only acquires raw bytes while the encoder owns the
 * downsample + compression policy (defaults: max-dim 2048, q=0.85 per §12 row 4).
 *
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
