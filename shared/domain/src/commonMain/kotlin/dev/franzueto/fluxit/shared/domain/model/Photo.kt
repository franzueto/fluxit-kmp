package dev.franzueto.fluxit.shared.domain.model

import kotlinx.datetime.Instant

/**
 * Persisted photo metadata. Photos are immutable once ingested (no
 * `updated_at` on the row — re-encoding produces a new row). `relativePath`
 * is relative to the app sandbox photo root; the platform layer resolves
 * to an absolute path at the image-loading site via
 * [dev.franzueto.fluxit.shared.domain.port.PhotoStorage.resolveAbsolute].
 *
 * [PhotoId] is declared in `ItemEntities.kt` (Items reference Photos via
 * FK); declared once, used in both repositories' contracts.
 */
public data class Photo(
    val id: PhotoId,
    val relativePath: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Long,
    val createdAt: Instant,
)
