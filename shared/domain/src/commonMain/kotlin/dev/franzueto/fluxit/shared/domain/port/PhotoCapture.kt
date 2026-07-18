package dev.franzueto.fluxit.shared.domain.port

import dev.franzueto.fluxit.shared.domain.error.Outcome

public data class CapturedPhoto(
    val bytes: ByteArray,
    val mime: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    // ByteArray needs structural equals/hashCode for value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedPhoto) return false
        return mime == other.mime &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + widthPx
        result = 31 * result + heightPx
        return result
    }
}

public sealed class CaptureError {
    /** The OS denied camera / photo-library permission. UI should prompt + retry. */
    public data object PermissionDenied : CaptureError()

    /** The user backed out of the camera / picker. Not an error to log — just abort quietly. */
    public data object UserCancelled : CaptureError()

    public data class Unknown(
        val cause: Throwable?,
    ) : CaptureError()
}

public interface PhotoCapture {
    /** Opens the camera UI. */
    public suspend fun capture(): Outcome<CapturedPhoto, CaptureError>

    /** Opens the system photo picker. */
    public suspend fun pickFromLibrary(): Outcome<CapturedPhoto, CaptureError>
}
