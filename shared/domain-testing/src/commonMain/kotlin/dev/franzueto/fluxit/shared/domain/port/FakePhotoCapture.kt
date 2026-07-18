package dev.franzueto.fluxit.shared.domain.port

import dev.franzueto.fluxit.shared.domain.error.Outcome

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
