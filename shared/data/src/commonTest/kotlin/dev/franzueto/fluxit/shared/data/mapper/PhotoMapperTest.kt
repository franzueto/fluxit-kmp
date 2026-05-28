package dev.franzueto.fluxit.shared.data.mapper

import dev.franzueto.fluxit.shared.domain.model.Photo
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.franzueto.fluxit.shared.data.db.Photo as PhotoRow

class PhotoMapperTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    @Test
    fun photoRow_to_domain_round_trips_with_dimension_narrowing() {
        // SQLite stores INTEGER → Long; domain narrows to Int for px
        // dimensions (no photo dimensions exceed 2^31 — see §12 row 4's
        // re-encode-to-2048-longest-side cap). byte_size stays Long
        // because file sizes routinely exceed Int range for v2.
        val row =
            PhotoRow(
                id = "00000000-0000-4000-8000-000000000030",
                relative_path = "photos/2026/05/28/abc.jpg",
                mime_type = "image/jpeg",
                width_px = 2048L,
                height_px = 1536L,
                byte_size = 524_288L,
                created_at = now,
                deleted_at = null,
            )

        assertEquals(
            Photo(
                id = PhotoId("00000000-0000-4000-8000-000000000030"),
                relativePath = "photos/2026/05/28/abc.jpg",
                mimeType = "image/jpeg",
                widthPx = 2048,
                heightPx = 1536,
                byteSize = 524_288L,
                createdAt = now,
            ),
            row.toDomain(),
        )
    }
}
