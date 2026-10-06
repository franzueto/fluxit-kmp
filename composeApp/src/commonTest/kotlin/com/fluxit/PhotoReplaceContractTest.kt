package com.fluxit

import com.fluxit.data.PhotoContent
import com.fluxit.data.newPhotoId
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.replacePhoto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * FB-302's pure contract tests: [newPhotoId] generation and [replacePhoto]'s documented
 * failure/ordering semantics, exercised entirely against [FakePhotoStorage] - no platform
 * code, no Firebase SDK, matching the acceptance criterion ("contract tests and recorded
 * failure semantics") directly.
 */
class PhotoIdGenerationTest {

    @Test
    fun neverContainsASlash() {
        repeat(200) {
            assertTrue('/' !in newPhotoId(), "photoId must never contain '/' per PLAN-006")
        }
    }

    @Test
    fun isNotBlank() {
        assertTrue(newPhotoId().isNotBlank())
    }

    @Test
    fun successiveIdsAreDistinct() {
        val ids = (1..500).map { newPhotoId() }
        assertEquals(ids.size, ids.toSet().size, "random photoId generation must not collide in practice")
    }

    @Test
    fun isUsableDirectlyAsThePhotoRefFinalSegment() {
        // Round-trips through the exact validating builder production code uses - proves
        // newPhotoId()'s output is never rejected by PLAN-006's own enforcement.
        val ref = FirebaseSchema.photoRef("uid-1", "item-1", newPhotoId())
        assertEquals("item-1", FirebaseSchema.itemIdFromPhotoRef(ref))
    }
}

class PhotoReplaceContractTest {

    @Test
    fun happyPathUploadsNewCommitsRefThenDeletesOld() = runTest {
        val storage = FakePhotoStorage()
        val oldRef = storage.uploadPhoto("item-1", byteArrayOf(1))
        var committedRef: String? = null

        val newRef = replacePhoto(
            storage = storage,
            itemId = "item-1",
            oldPhotoRef = oldRef,
            newBytes = byteArrayOf(2),
            updateRef = { ref -> committedRef = ref },
        )

        assertEquals(newRef, committedRef)
        assertNotEquals(oldRef, newRef, "old and new objects must coexist at different refs")
        assertNull(storage.objects[oldRef], "old object is deleted only after the new ref is committed")
        assertEquals(byteArrayOf(2).toList(), storage.objects[newRef]?.toList())
    }

    @Test
    fun uploadFailureLeavesTheOldPhotoFullyIntactAndNeverCallsUpdateRef() = runTest {
        val storage = FakePhotoStorage().apply { failUpload = IllegalStateException("boom") }
        val oldRef = "users/uid-1/items/item-1/old-photo"
        storage.objects[oldRef] = byteArrayOf(9)
        var updateRefCalled = false

        assertFailsWith<IllegalStateException> {
            replacePhoto(
                storage = storage,
                itemId = "item-1",
                oldPhotoRef = oldRef,
                newBytes = byteArrayOf(2),
                updateRef = { updateRefCalled = true },
            )
        }

        assertTrue(!updateRefCalled, "a failed upload must never reach the document write")
        assertEquals(byteArrayOf(9).toList(), storage.objects[oldRef]?.toList(), "old photo must survive an upload failure")
        assertEquals(1, storage.objects.size, "no new object should be left behind by a failed upload")
    }

    @Test
    fun documentWriteFailureLeavesTheOldPhotoReferencedAndIntact() = runTest {
        val storage = FakePhotoStorage()
        val oldRef = storage.uploadPhoto("item-1", byteArrayOf(9))

        assertFailsWith<IllegalStateException> {
            replacePhoto(
                storage = storage,
                itemId = "item-1",
                oldPhotoRef = oldRef,
                newBytes = byteArrayOf(2),
                updateRef = { throw IllegalStateException("firestore write failed") },
            )
        }

        // The old object is still there and still resolvable - the caller never updated its
        // local photoRef, so the item still (correctly) points at it.
        assertEquals(byteArrayOf(9).toList(), storage.objects[oldRef]?.toList())
        val preview = storage.loadPhoto(oldRef)
        assertIs<PhotoContent.Bytes>(preview)
        // The newly uploaded object is real (an accepted, sweep-reclaimable orphan) - the
        // failure is in the *document write*, not the upload.
        assertEquals(2, storage.objects.size)
    }

    @Test
    fun deleteFailureDoesNotFailTheReplaceAndTheItemEndsUpReferencingTheNewPhoto() = runTest {
        val storage = FakePhotoStorage().apply { failDelete = IllegalStateException("delete boom") }
        val oldRef = storage.uploadPhoto("item-1", byteArrayOf(9))
        var committedRef: String? = null

        val newRef = replacePhoto(
            storage = storage,
            itemId = "item-1",
            oldPhotoRef = oldRef,
            newBytes = byteArrayOf(2),
            updateRef = { ref -> committedRef = ref },
        )

        assertEquals(newRef, committedRef, "the item must end up referencing the new photo even if the old delete fails")
        assertEquals(byteArrayOf(2).toList(), storage.objects[newRef]?.toList())
        // The old object is leaked (delete failed) rather than lost - never a dangling
        // reference, since the item was never pointed at it after this call.
        assertEquals(byteArrayOf(9).toList(), storage.objects[oldRef]?.toList())
    }

    @Test
    fun firstUploadHasNoOldPhotoToDelete() = runTest {
        val storage = FakePhotoStorage()
        var committedRef: String? = null

        val newRef = replacePhoto(
            storage = storage,
            itemId = "item-1",
            oldPhotoRef = null,
            newBytes = byteArrayOf(1),
            updateRef = { ref -> committedRef = ref },
        )

        assertEquals(newRef, committedRef)
        assertEquals(1, storage.objects.size)
    }
}
