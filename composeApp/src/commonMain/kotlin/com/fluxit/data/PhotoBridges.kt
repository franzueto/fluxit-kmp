package com.fluxit.data

/** Launches the system photo picker and returns the picked image bytes, or null if cancelled. */
interface PhotoPicker {
    suspend fun pickPhoto(): ByteArray?
}

/**
 * A resolved, renderable form of a photo object. Deliberately not a single type: today's
 * platform adapters ([FB-302's local-file stubs][PhotoStorage]) hand back a loadable path,
 * while a real Cloud Storage adapter (`FB-304`/`FB-305`, not yet built) may instead download
 * bytes directly. Never a Firebase SDK type - this is exactly the boundary Firebase-touching
 * platform code must stay behind (see [PhotoStorage]).
 */
sealed interface PhotoContent {
    /** Already-available bytes, ready to hand to an image decoder. */
    data class Bytes(val bytes: ByteArray) : PhotoContent

    /** A URI/path the platform's own image decoder can load directly - e.g. a local file
     * path today, or a cached-download path/platform image-loader URL once `FB-304`/`FB-305`
     * land. */
    data class Loadable(val uri: String) : PhotoContent
}

/**
 * Firebase-neutral contract for the durable, remote-object-backed photo store behind an
 * item's `photoRef` (`com.fluxit.data.remote.FirebaseSchema.photoRef`, PLAN-005/PLAN-006/
 * PLAN-007). No Firebase SDK type may appear here or anywhere else in `commonMain`; platform
 * adapters (`AndroidPhotoStorage`/`IosPhotoStorage`) are the only place allowed to depend on
 * a concrete backing store. **Today both adapters are still local-file stubs** that merely
 * produce/consume correctly-shaped `photoRef` strings - real Cloud Storage upload/download/
 * delete is `FB-304` (Android) and `FB-305` (iOS), deliberately not this contract's job, and
 * image validation/resize/compression is `FB-303`, also deliberately not here.
 *
 * ### `photoRef` shape (PLAN-006)
 * [uploadPhoto] returns a `photoRef` built by `FirebaseSchema.photoRef`: exactly
 * `users/{uid}/items/{itemId}/{photoId}` - three fixed segments, no recursive wildcard, and
 * `photoId` never contains `/` (see [newPhotoId]). [loadPhoto]/[deletePhoto] take that exact
 * string back unmodified; neither of them constructs or parses it.
 *
 * ### Orphan-reconciliation contract (PLAN-007; the sweep itself is `FB-502`/`FB-503`, not
 * implemented here)
 * Storage photo paths carry no `listId`, so the future sweep can only key eligibility on
 * `itemId` (`FirebaseSchema.itemIdFromPhotoRef`). This interface must never do anything that
 * would make that keying, or `DEC-003e`/`DEC-003e-2`'s 30-day-grace-period /
 * tombstone-still-referenced rule, impossible later:
 *  - [uploadPhoto] must never encode a `listId` anywhere in the returned ref.
 *  - Nothing in this interface deletes an object except an explicit [deletePhoto] call - in
 *    particular, [uploadPhoto] must never delete or overwrite the object it is replacing.
 *    Only an explicit [deletePhoto] call, or the future sweep after its grace period, may
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
     */
    suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String

    /**
     * Resolves [photoRef] to renderable [PhotoContent], or `null` if it does not exist or is
     * not accessible.
     */
    suspend fun loadPhoto(photoRef: String): PhotoContent?

    /**
     * Deletes the object at [photoRef]. MUST be idempotent: deleting an already-missing
     * object is not an error, since a retried/duplicate delete call (e.g. from a client that
     * failed to observe an earlier call's success) is expected, not exceptional.
     */
    suspend fun deletePhoto(photoRef: String)
}

/**
 * Deterministic-*shape*, collision-resistant-*value* `photoId` generation, reusing [newId]
 * (the same random-UUID generator already used for list/item ids).
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
