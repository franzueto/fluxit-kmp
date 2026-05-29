package dev.franzueto.fluxit.shared.domain.port

import dev.franzueto.fluxit.shared.domain.error.Outcome

/**
 * Reusable test fixture for the [PhotoCapture] port (Phase 04 §11 / Slice 13D).
 * Each method returns its configured [Outcome] and records a call count so
 * tests can assert which source the use case opened and exercise the
 * [CaptureError] branches.
 */
public class FakePhotoCapture(
    public var captureResult: Outcome<CapturedPhoto, CaptureError> = Outcome.Ok(DEFAULT_PHOTO),
    public var libraryResult: Outcome<CapturedPhoto, CaptureError> = Outcome.Ok(DEFAULT_PHOTO),
) : PhotoCapture {
    public var captureCalls: Int = 0
        private set

    public var libraryCalls: Int = 0
        private set

    override suspend fun capture(): Outcome<CapturedPhoto, CaptureError> {
        captureCalls++
        return captureResult
    }

    override suspend fun pickFromLibrary(): Outcome<CapturedPhoto, CaptureError> {
        libraryCalls++
        return libraryResult
    }

    public companion object {
        public val DEFAULT_PHOTO: CapturedPhoto =
            CapturedPhoto(
                bytes = byteArrayOf(1, 2, 3, 4),
                mime = "image/jpeg",
                widthPx = 2048,
                heightPx = 1536,
            )
    }
}
