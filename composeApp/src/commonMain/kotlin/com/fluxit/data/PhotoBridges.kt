package com.fluxit.data

import com.fluxit.data.remote.ApplicationError

/** Launches the system photo picker and returns the picked image bytes, or null if cancelled. */
interface PhotoPicker {
    suspend fun pickPhoto(): ByteArray?
}

/** Downloaded or locally prepared image bytes, ready for platform decoding.
 * Firebase SDK types remain behind the platform adapters. */
sealed interface PhotoContent {
    /** Already-available bytes, ready to hand to an image decoder. */
    data class Bytes(val bytes: ByteArray) : PhotoContent
}

/**
 * Firebase-neutral contract for the durable, remote-object-backed photo store behind an
 * item's `photoRef` (`com.fluxit.data.remote.FirebaseSchema.photoRef`, PLAN-005/PLAN-006/
 * PLAN-007). No Firebase SDK type may appear here or anywhere else in `commonMain`; platform
 * adapters (`AndroidPhotoStorage`/`IosPhotoStorage`) are the only place allowed to depend on
 * a concrete backing store. Both adapters upload/download/delete through Firebase Cloud
 * Storage. Image validation/resize/compression runs before upload via [preparePhotoForUpload].
 *
 * ### `photoRef` shape (PLAN-006)
 * [uploadPhoto] returns a `photoRef` built by `FirebaseSchema.photoRef`: exactly
 * `users/{uid}/items/{itemId}/{photoId}` - three fixed segments, no recursive wildcard, and
 * `photoId` never contains `/` (see [newPhotoId]). [loadPhoto]/[deletePhoto] take that exact
 * string back unmodified; neither of them constructs or parses it.
 *
 * ### Orphan-reconciliation contract (PLAN-007; backend sweep in `FB-502`/`FB-503`)
 * Storage photo paths carry no `listId`, so the backend sweep can only key eligibility on
 * `itemId` (`FirebaseSchema.itemIdFromPhotoRef`). This interface must never do anything that
 * would make that keying, or `DEC-003e`/`DEC-003e-2`'s 30-day-grace-period /
 * tombstone-still-referenced rule, impossible later:
 *  - [uploadPhoto] must never encode a `listId` anywhere in the returned ref.
 *  - Nothing in this interface deletes an object except an explicit [deletePhoto] call - in
 *    particular, [uploadPhoto] must never delete or overwrite the object it is replacing.
 *    Only an explicit [deletePhoto] call, or the backend sweep after its grace period, may
 *    ever remove an object, so there is exactly one deletion path for the sweep's
 *    still-referenced check to reason about.
 *
 * ### Replace-ordering / failure semantics (Phase 3 plan text)
 * Callers replacing a photo MUST perform, in this order:
 *  1. [uploadPhoto] the new bytes. The old `photoRef`, if any, is completely untouched.
 *  2. Persist the *returned* `photoRef` on the item's document (e.g.
 *     `ItemRepository.setPhotoRef`) - only after step 1 succeeds.
 *  3. [deletePhoto] the *old* `photoRef` - only after step 2 succeeds.
 *
 * [replacePhoto] is the single implementation of this ordering; callers (e.g.
 * `ItemDetailViewModel`) use it rather than re-deriving it. Consequence of the ordering,
 * spelled out because it is exactly what the contract tests prove:
 *  - **Upload failure:** the item still references the old, still-loadable photo. Nothing
 *    new was ever created, so nothing is orphaned.
 *  - **Document-write failure (step 2):** the item still references the old, still-loadable
 *    photo. The newly uploaded object is real but unreferenced - not a bug, exactly the kind
 *    of object `DEC-003e`'s grace-period sweep exists to reclaim later.
 *  - **Delete failure (step 3):** the item already, correctly, references the new photo. The
 *    old object is merely leaked (again, sweep-reclaimable) - never treated as a reason to
 *    fail the whole replace, since the state that matters (what the item points at) is
 *    already correct.
 *
 * No failure at any single step can lose a still-referenced photo or leave the item pointing
 * at an object that was never created.
 */
interface PhotoStorage {
    /**
     * Uploads [bytes] as a brand-new object for [itemId] and returns its `photoRef`. Always
     * creates a new object (see [newPhotoId]) - never overwrites or deletes any existing
     * object, including a previous photo for the same item - so the safe-replace ordering
     * documented above is achievable by construction, not by caller discipline alone.
     *
     * `FB-403`: any failure - other than [loadPhoto]/[deletePhoto]'s documented "missing
     * object" no-throw case - is surfaced as [PhotoStorageException], never a raw
     * platform/Firebase SDK exception instance. See [PhotoStorageException]'s own KDoc.
     */
    suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String

    /**
     * Resolves [photoRef] to renderable [PhotoContent], or `null` if it does not exist or is
     * not accessible. Any other failure is surfaced as [PhotoStorageException] (`FB-403`).
     */
    suspend fun loadPhoto(photoRef: String): PhotoContent?

    /**
     * Deletes the object at [photoRef]. MUST be idempotent: deleting an already-missing
     * object is not an error, since a retried/duplicate delete call (e.g. from a client that
     * failed to observe an earlier call's success) is expected, not exceptional. Any other
     * failure is surfaced as [PhotoStorageException] (`FB-403`).
     */
    suspend fun deletePhoto(photoRef: String)
}

/**
 * `FB-403`: thrown by both platforms' [PhotoStorage] adapters (`AndroidPhotoStorage`,
 * `IosPhotoStorage`) instead of ever letting a raw platform/Firebase SDK exception instance
 * cross into `commonMain`-visible code - discharges the remainder of `FB-305-NB2` and closes
 * `FB-401-NB1`/`FB-401-NB2`. `FB-401` already gave both platforms' Storage SDK exception types
 * a neutral `.toApplicationError()` mapping (`AndroidFirebaseStorageErrorMapping.kt`,
 * `IosFirebaseStorageErrorMapping.kt`) but left it unused at any real call site; this type is
 * what each adapter now wraps that mapped [ApplicationError] in before throwing, so a
 * `commonMain` caller (`ItemDetailViewModel`, [replacePhoto]) only ever needs to understand
 * this one neutral type - never a platform-specific exception class - to react to a
 * photo-storage failure. Exactly the shape `com.fluxit.firebase.list.ListRepositoryException`
 * already established for `ListRepository`, except deliberately promoted to `commonMain`
 * (rather than kept `internal` to one platform source set) precisely so callers here *can*
 * decode the [error] payload - `ListRepositoryException`'s platform-`internal` visibility is
 * exactly the still-open gap `FB-402-NB1` tracks for list operations, not repeated here.
 */
class PhotoStorageException(val error: ApplicationError) : Exception()

/**
 * Deterministic-*shape*, collision-resistant-*value* `photoId` generation, reusing [newId]
 * (the random-UUID generator retained for photo object ids).
 *
 * Justification against the alternatives considered:
 *  - **A stable id per item** (e.g. always `"photo"`) is wrong: [PhotoStorage]'s safe-replace
 *    ordering requires the old and new objects to coexist at *different* addresses until the
 *    old one is explicitly deleted. A stable id would overwrite the old object at upload
 *    time - before the referencing document is ever updated - destroying it and defeating
 *    the entire ordering guarantee the very first time a replace's later step failed.
 *  - **A timestamp/counter-based id** can collide: the same account can upload from two
 *    devices for the same item in close succession (or a device clock can be wrong), and
 *    nothing in this interface serializes calls across devices or processes.
 *  - **A random v4 UUID** costs one extra object per replace - already implied by the
 *    ordering above regardless of id strategy - but has a collision probability low enough
 *    to ignore in practice, is guaranteed never to contain `/` by construction (satisfies
 *    PLAN-006 without needing to escape/validate arbitrary input), and needs no coordination
 *    or shared counter state across devices.
 */
fun newPhotoId(): String = newId()

/**
 * Executes the mandated safe photo-replace ordering documented on [PhotoStorage]: upload,
 * then persist the new ref via [updateRef], then best-effort delete the old object. Returns
 * the new `photoRef`.
 *
 * A failure in [PhotoStorage.uploadPhoto] or [updateRef] propagates to the caller unchanged:
 * nothing has been committed in either case, so the caller's existing state (the old
 * `photoRef`, still valid) needs no correction. A failure in the final, best-effort
 * [PhotoStorage.deletePhoto] call is deliberately swallowed - by that point [updateRef] has
 * already succeeded, so the item already, correctly, references the new photo; the old
 * object becoming a leaked-but-sweep-reclaimable orphan is the documented, acceptable
 * outcome, not a reason to report the whole replace as failed.
 */
suspend fun replacePhoto(
    storage: PhotoStorage,
    itemId: String,
    oldPhotoRef: String?,
    newBytes: ByteArray,
    updateRef: suspend (String) -> Unit,
): String {
    val newRef = storage.uploadPhoto(itemId, newBytes)
    updateRef(newRef)
    if (oldPhotoRef != null) {
        runCatching { storage.deletePhoto(oldPhotoRef) }
    }
    return newRef
}
