package com.fluxit.data

/**
 * An image type this app is willing to accept as a photo attachment, detected by sniffing the
 * bytes' own magic number - never by trusting a caller-supplied MIME string or file extension,
 * which is easy to spoof and does not guarantee the bytes actually decode as that format. See
 * [ImageSniffer].
 */
enum class ImageType(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
}

/**
 * size/type/resize policy constants. Concrete values, and the reasoning behind them,
 * documented here for reviewer visibility (flagged as judgment calls, not values dictated by
 * the plan text itself):
 *
 *  - [MAX_SOURCE_BYTES] (25 MB): a hard, pre-decode ceiling on the *original* picked bytes.
 *    Decoding a full image frame allocates roughly `width * height * 4` bytes of raw pixel
 *    memory - commonly 50-100x the encoded file size - so a file is only ever handed to a
 *    platform decoder ([readImageDimensions]/[resizeImage]) once it is already known to be at
 *    most this large, bounding worst-case decode memory regardless of how the file arrived.
 *  - [MAX_UPLOAD_BYTES] (5 MB): the ceiling enforced on the *final* bytes handed to
 *    `PhotoStorage.uploadPhoto`, after any resize/recompress. Large enough for a modern phone
 *    camera photo re-encoded at [JPEG_QUALITY], small enough to keep Cloud Storage cost and
 *    mobile upload latency bounded on a typical connection - directly the plan's "control
 *    latency, memory, and Storage cost" goal.
 *  - [MAX_DIMENSION_PX] (2048 px, long edge): comfortably larger than any on-screen preview
 *    this app renders (`ItemDetailScreen`'s photo preview), while capping decode/re-encode
 *    memory and upload size for camera photos that are routinely 4000px+ on the long edge.
 *  - [JPEG_QUALITY] (82): the re-encode quality used whenever [preparePhotoForUpload] resizes
 *    or recompresses. A standard "visually lossless for photographic content, meaningfully
 *    smaller file" JPEG quality. [resizeImage] always re-encodes to JPEG regardless of the
 *    source type (including PNG/WebP input) - a photo attachment does not need PNG's
 *    lossless/alpha guarantees, and JPEG is universally decodable by both platforms' image
 *    loaders.
 *  - [SUPPORTED_TYPES] (JPEG, PNG, WebP): the image formats every current Android
 *    (`ActivityResultContracts.PickVisualMedia.ImageOnly`) and iOS (`PHPickerFilter.imagesFilter`)
 *    system photo-picker selection can hand back. Formats [ImageSniffer] recognizes but this
 *    set excludes (GIF, BMP) are rejected as [PhotoRejected.UnsupportedType] rather than
 *    silently reinterpreted. HEIC is deliberately not decoded/supported here: both system
 *    pickers already re-encode HEIC captures to JPEG on export in normal use, so adding HEIC
 *    decoding would only mask picker misbehavior rather than serve a real user path, and would
 *    require a new platform dependency neither `android.graphics`/`Skia` provide for free.
 */
object PhotoPolicy {
    const val MAX_SOURCE_BYTES: Int = 25 * 1024 * 1024
    const val MAX_UPLOAD_BYTES: Int = 5 * 1024 * 1024
    const val MAX_DIMENSION_PX: Int = 2048
    const val JPEG_QUALITY: Int = 82
    val SUPPORTED_TYPES: Set<ImageType> = setOf(ImageType.JPEG, ImageType.PNG, ImageType.WEBP)
}

/**
 * Rejection reasons [validatePhotoSource]/[preparePhotoForUpload] can raise, typed and minimal
 * so UI error-state code can pattern-match on *why* a photo was
 * rejected without parsing message strings. Never a Firebase exception type - this layer runs
 * entirely before any `PhotoStorage`/Storage call (see `PhotoBridges.kt`).
 */
sealed class PhotoRejected(message: String) : Exception(message) {
    /**
     * [actualBytes] exceeded [maxBytes]. Raised either for a source file too large to safely
     * decode ([PhotoPolicy.MAX_SOURCE_BYTES]) or, defensively, for output that is still too
     * large to upload after resize/recompress ([PhotoPolicy.MAX_UPLOAD_BYTES]) - an edge case
     * for very high-detail/noisy images that do not compress well even at
     * [PhotoPolicy.JPEG_QUALITY].
     */
    class TooLarge(val actualBytes: Int, val maxBytes: Int) :
        PhotoRejected("Image is $actualBytes bytes, exceeds the $maxBytes byte limit")

    /** Bytes look like a real, decodable image, but not one of [PhotoPolicy.SUPPORTED_TYPES]. */
    class UnsupportedType(val detected: String) :
        PhotoRejected("Unsupported image type: $detected")

    /**
     * Bytes do not match any recognized image signature, or a recognized-looking header still
     * fails to decode (a truncated/malformed file).
     */
    object Corrupt : PhotoRejected("Image data is corrupt or unrecognized")
}

/** The outcome of sniffing an image's leading bytes - see [ImageSniffer.detect]. */
sealed interface SniffResult {
    /** A recognized, supported-or-not image signature. */
    data class Recognized(val type: ImageType) : SniffResult

    /** A recognized image signature that is not in [PhotoPolicy.SUPPORTED_TYPES], carrying a
     * short label for [PhotoRejected.UnsupportedType]'s message. */
    data class UnsupportedSignature(val label: String) : SniffResult

    /** No known image signature matched at all. */
    object Unrecognized : SniffResult
}

/**
 * Detects an image's type from its leading magic bytes - the same technique real Cloud Storage
 * backends and browsers use, since a caller-supplied MIME/extension can be wrong or spoofed but
 * the encoded byte signature cannot be produced by anything other than that format's encoder
 * (or a deliberately crafted corrupt file, which is exactly what [PhotoRejected.Corrupt] exists
 * to catch downstream in [validatePhotoSource]). Recognizes a superset of
 * [PhotoPolicy.SUPPORTED_TYPES] (GIF, BMP) so callers can distinguish "a real image we don't
 * support" ([SniffResult.UnsupportedSignature]) from "not recognizable as an image at all"
 * ([SniffResult.Unrecognized]). Pure byte inspection only - never decodes pixel data.
 */
object ImageSniffer {
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF_MAGIC = byteArrayOf(0x52, 0x49, 0x46, 0x46) // "RIFF"
    private val WEBP_MAGIC = byteArrayOf(0x57, 0x45, 0x42, 0x50) // "WEBP"
    private val GIF87_MAGIC = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) // "GIF87a"
    private val GIF89_MAGIC = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) // "GIF89a"
    private val BMP_MAGIC = byteArrayOf(0x42, 0x4D) // "BM"

    fun detect(bytes: ByteArray): SniffResult {
        if (bytes.matchesAt(0, JPEG_MAGIC)) return SniffResult.Recognized(ImageType.JPEG)
        if (bytes.matchesAt(0, PNG_MAGIC)) return SniffResult.Recognized(ImageType.PNG)
        if (bytes.matchesAt(0, RIFF_MAGIC) && bytes.matchesAt(8, WEBP_MAGIC)) {
            return SniffResult.Recognized(ImageType.WEBP)
        }
        if (bytes.matchesAt(0, GIF87_MAGIC) || bytes.matchesAt(0, GIF89_MAGIC)) {
            return SniffResult.UnsupportedSignature("gif")
        }
        if (bytes.matchesAt(0, BMP_MAGIC)) return SniffResult.UnsupportedSignature("bmp")
        return SniffResult.Unrecognized
    }

    private fun ByteArray.matchesAt(offset: Int, magic: ByteArray): Boolean {
        if (size < offset + magic.size) return false
        for (i in magic.indices) if (this[offset + i] != magic[i]) return false
        return true
    }
}

/**
 * Validates [bytes] as an acceptable photo-attachment source: within [PhotoPolicy.MAX_SOURCE_BYTES]
 * and a supported, recognized image type. Throws [PhotoRejected] and never returns normally on
 * any rejection; returns the detected [ImageType] on success. Deliberately does not check
 * [PhotoPolicy.MAX_UPLOAD_BYTES] here - that limit applies to the final, possibly-resized bytes
 * and is enforced by [preparePhotoForUpload], not on the original source.
 */
fun validatePhotoSource(bytes: ByteArray): ImageType {
    if (bytes.size > PhotoPolicy.MAX_SOURCE_BYTES) {
        throw PhotoRejected.TooLarge(bytes.size, PhotoPolicy.MAX_SOURCE_BYTES)
    }
    return when (val result = ImageSniffer.detect(bytes)) {
        is SniffResult.Recognized -> {
            if (result.type !in PhotoPolicy.SUPPORTED_TYPES) {
                throw PhotoRejected.UnsupportedType(result.type.mimeType)
            }
            result.type
        }
        is SniffResult.UnsupportedSignature -> throw PhotoRejected.UnsupportedType(result.label)
        SniffResult.Unrecognized -> throw PhotoRejected.Corrupt
    }
}

/**
 * Validates and, if needed, resizes/recompresses [bytes] into a form ready for
 * `PhotoStorage.uploadPhoto`. This is the one function a caller (`ItemDetailViewModel.pickPhoto`)
 * runs on freshly picked bytes *before* calling `uploadPhoto`/`replacePhoto` - it never touches
 * `PhotoStorage` or Firestore itself, so it composes ahead of, and is fully independent of,
 * Safe-replace ordering (see `PhotoBridges.kt`).
 *
 * Decision logic (pure, lives entirely in this function): validate the source via
 * [validatePhotoSource], read its pixel dimensions via [readDimensions], and resize via
 * [resize] only if the source exceeds either [PhotoPolicy.MAX_DIMENSION_PX] on its long edge or
 * [PhotoPolicy.MAX_UPLOAD_BYTES] in size - an already-small image is uploaded byte-for-byte
 * rather than always being re-encoded, which would lose quality for no size benefit.
 * [readDimensions]/[resize] are injected (defaulting to the real [readImageDimensions]/
 * [resizeImage] expect/actual pair) precisely so this decision logic is pure-unit-testable with
 * fakes, independent of any platform image decoder.
 *
 * Throws [PhotoRejected.Corrupt] if [readDimensions] or [resize] cannot decode bytes that
 * passed [validatePhotoSource]'s magic-byte sniff (a truncated/malformed file with a
 * valid-looking header). Throws [PhotoRejected.TooLarge] if the resized output is, defensively,
 * still over [PhotoPolicy.MAX_UPLOAD_BYTES].
 */
fun preparePhotoForUpload(
    bytes: ByteArray,
    readDimensions: (ByteArray) -> ImageDimensions? = ::readImageDimensions,
    resize: (ByteArray, Int, Int) -> ByteArray? = ::resizeImage,
): ByteArray {
    validatePhotoSource(bytes)
    val dimensions = readDimensions(bytes) ?: throw PhotoRejected.Corrupt
    val longEdge = maxOf(dimensions.widthPx, dimensions.heightPx)
    val needsResize = longEdge > PhotoPolicy.MAX_DIMENSION_PX || bytes.size > PhotoPolicy.MAX_UPLOAD_BYTES
    val output = if (needsResize) {
        resize(bytes, PhotoPolicy.MAX_DIMENSION_PX, PhotoPolicy.JPEG_QUALITY) ?: throw PhotoRejected.Corrupt
    } else {
        bytes
    }
    if (output.size > PhotoPolicy.MAX_UPLOAD_BYTES) {
        throw PhotoRejected.TooLarge(output.size, PhotoPolicy.MAX_UPLOAD_BYTES)
    }
    return output
}
