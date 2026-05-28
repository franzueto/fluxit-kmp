package dev.franzueto.fluxit.shared.data.mapper

import dev.franzueto.fluxit.shared.domain.model.Photo
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.data.db.Photo as PhotoRow

internal fun PhotoRow.toDomain(): Photo =
    Photo(
        id = PhotoId(id),
        relativePath = relative_path,
        mimeType = mime_type,
        widthPx = width_px.toInt(),
        heightPx = height_px.toInt(),
        byteSize = byte_size,
        createdAt = created_at,
    )
