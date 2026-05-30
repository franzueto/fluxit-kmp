package dev.franzueto.fluxit.shared.domain.usecase.photos

import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.model.PhotoId
import dev.franzueto.fluxit.shared.domain.port.FakePhotoStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolvePhotoUriTest {
    @Test
    fun resolves_an_ingested_photo_to_an_absolute_uri() =
        runTest {
            val storage = FakePhotoStorage()
            val photos = newPhotosRepo(storage)
            val id = (photos.ingest(byteArrayOf(1, 2, 3), "image/jpeg", 4, 4) as Outcome.Ok).value

            val uri = ResolvePhotoUri(photos, storage)(id)
            // FakePhotoStorage mints "photos/<n>.bin" and resolves to "/fake-sandbox/<path>".
            assertEquals("/fake-sandbox/photos/1.bin", uri)
        }

    @Test
    fun returns_null_for_a_missing_photo() =
        runTest {
            val storage = FakePhotoStorage()
            val uri = ResolvePhotoUri(newPhotosRepo(storage), storage)(PhotoId("photo-99999999"))
            assertNull(uri)
        }
}
