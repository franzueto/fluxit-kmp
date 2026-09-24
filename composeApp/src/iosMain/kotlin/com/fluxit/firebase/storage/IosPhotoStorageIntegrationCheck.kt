package com.fluxit.firebase.storage

import com.fluxit.data.IosPhotoStorage
import com.fluxit.data.PhotoContent
import com.fluxit.data.newPhotoId
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.replacePhoto
import com.fluxit.data.toNSData
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.auth.AuthResult
import com.fluxit.firebase.IosFirebaseEmulatorSettings
import com.fluxit.firebase.auth.IosAuthBridgeRegistry
import com.fluxit.firebase.auth.IosAuthRepository
import com.fluxit.firebase.item.IosFirebaseItemRepository
import com.fluxit.firebase.list.IosFirebaseListRepository
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSUUID

/**
 * `FB-305` emulator-backed integration check for the iOS Storage adapter, in the exact
 * style FB-206's `IosFirestoreCrossClientIntegrationCheck` (itself following FB-103/FB-203/
 * FB-205) established: no Xcode test target exists in this repository, so this lives in the
 * app binary and is exercised by a real simulator run launched with a specific argument,
 * double-gated (emulator-only build config, plus the launch argument itself) so it can
 * never touch the live development project.
 *
 * What this proves that a fake-bridge unit test cannot: that the real Swift
 * `FirebaseStorageBridge` correctly talks to a real Firebase Storage SDK instance end to
 * end, against the real, deployed, owner-only `storage.rules` (`FB-005`) loaded by the
 * Storage emulator - the ledger's acceptance criterion, "iOS integration/manual checks pass
 * under Storage Rules" - mirroring `FB-304`'s Android `PhotoStorageEmulatorIntegrationTest`
 * shape and `FB-008`'s live cross-user Storage denial precedent.
 *
 * **"Cross-user" on iOS, and its one honest, disclosed divergence from Android's shape:**
 * Android's `PhotoStorageEmulatorIntegrationTest` opens two independently named secondary
 * `FirebaseApp`/`FirebaseAuth`/`FirebaseStorage` instances, so client A and client B are
 * genuinely simultaneous, distinct SDK connections. Per PLAN-008/FB-206's already-documented
 * iOS constraint, this app registers exactly one Swift `FirebaseStorageBridge`/
 * `FirebaseAuthBridge` implementation against the single default `FirebaseApp`, and neither
 * bridge protocol exposes a way to open a second named app instance. This check therefore
 * signs client A out and client B in **serially** on that one shared connection (real
 * distinct uids, real distinct ID tokens, real Rules evaluation - not a simulation), which
 * is sufficient to prove a genuine cross-user Rules denial (Storage Rules evaluate the
 * request's current auth token, not connection identity), but not simultaneous multi-device
 * access - the same class of narrow, disclosed gap FB-206 already accepted for the
 * equivalent Firestore check. Adding a network-toggle-equivalent multi-app bridge is a
 * production bridge-protocol change, out of this task's scope.
 *
 * `FB-306` (discharging `FB-305-NB1`) added the final section below: [replacePhoto] driven
 * against a real [IosFirebaseItemRepository] (Firestore) *and* this file's real
 * [IosPhotoStorage] (Storage) together, proving the documented safe-replace ordering commits
 * correctly end to end on real infrastructure, not just against `PhotoReplaceContractTest`'s
 * `commonTest` fake. Genuine mid-operation failure injection against a *real* Firestore/
 * Storage backend is not available here - `IosFirestoreItemBridge`/[IosPhotoStorage] expose
 * no network-toggle surface (`FB-206-NB1`'s already-accepted, still-open constraint; adding
 * one is a production bridge-protocol change, out of scope) - so this section proves the
 * happy path genuinely commits through both real backends together; the failure/preservation
 * semantics themselves are already exhaustively proven by `PhotoReplaceContractTest` and, at
 * the UI layer, `ItemDetailViewModelTest` (`FB-306`).
 */
object IosPhotoStorageIntegrationCheck {

    private const val PASSWORD = "fb305-emulator-only"
    private const val SETTLE_MS = 400L

    // A minimal, valid, hand-verifiable 1x1 transparent PNG - the same fixture
    // `ImageTransformIosTest` (FB-303, iosTest source set) uses, duplicated here rather
    // than shared because this file lives in iosMain (shipped in the app binary) and
    // cannot depend on iosTest code.
    @OptIn(ExperimentalEncodingApi::class)
    private val onePixelPng: ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )

    /**
     * Exported to Swift as a completion-handler function that must never itself throw -
     * same load-bearing outer guard `IosFirestoreCrossClientIntegrationCheck.run`'s KDoc
     * documents.
     */
    suspend fun run(): String = try {
        runChecked()
    } catch (throwable: Throwable) {
        "FB-305 iOS Storage integration check: THREW ${throwable::class.simpleName}: " +
            "${throwable.message}\nFB-305 END"
    }

    private suspend fun runChecked(): String {
        val report = Report()
        if (!IosFirebaseEmulatorSettings.enabled) {
            report.fail("preconditions", "emulator mode is disabled; refusing to run against a live project")
            return report.render()
        }
        val storageBridge = IosFirebaseStorageBridgeRegistry.bridgeOrNull()
        val authBridge = IosAuthBridgeRegistry.bridgeOrNull()
        if (storageBridge == null || authBridge == null) {
            report.fail("bridge registration", "storageBridge=$storageBridge authBridge=$authBridge")
            return report.render()
        }
        report.pass(
            "preconditions",
            "emulator enabled at ${IosFirebaseEmulatorSettings.host}:${IosFirebaseEmulatorSettings.storagePort}",
        )

        val auth = IosAuthRepository()
        // The production adapter, reading currentUid live off the single shared Auth
        // session - see the class KDoc for why cross-user coverage below is serial
        // (sign-out/sign-in), not simultaneous, on iOS.
        val storage = IosPhotoStorage()
        val suffix = NSUUID().UUIDString().lowercase()
        val emailA = "fb305-a-$suffix@example.com"
        val emailB = "fb305-b-$suffix@example.com"
        val itemId = NSUUID().UUIDString().lowercase()

        report.expectSuccess("client A sign-up", auth.signUp(emailA, PASSWORD))
        delay(SETTLE_MS)
        val uidA = authBridge.currentUser()?.uid
        if (uidA == null) {
            report.fail("preconditions", "client A sign-up did not resolve a uid")
            return report.render()
        }

        // --- owner: real upload/load/delete round trip, at the exact photoRef shape ----
        val photoRef = storage.uploadPhoto(itemId, onePixelPng)
        report.check(
            "uploadPhoto mints the exact photoRef shape (users/{uid}/items/{itemId}/{photoId})",
            photoRef.substringBeforeLast('/') == "users/$uidA/items/$itemId",
            "photoRef=$photoRef",
        )
        report.check(
            "itemIdFromPhotoRef recovers the same itemId",
            FirebaseSchema.itemIdFromPhotoRef(photoRef) == itemId,
            "photoRef=$photoRef",
        )
        val loaded = storage.loadPhoto(photoRef)
        report.check(
            "owner can load the uploaded bytes back byte-identical",
            loaded is PhotoContent.Bytes && loaded.bytes.contentEquals(onePixelPng),
            "loaded=$loaded",
        )
        storage.deletePhoto(photoRef)
        report.check(
            "the object is gone immediately after delete",
            storage.loadPhoto(photoRef) == null,
            "",
        )

        // --- missing-object semantics: null on load, silent idempotent no-op on delete --
        val neverUploadedRef = FirebaseSchema.photoRef(uidA, itemId, newPhotoId())
        report.check(
            "loading a missing object returns null instead of throwing",
            storage.loadPhoto(neverUploadedRef) == null,
            "",
        )
        val firstDelete = runCatching { storage.deletePhoto(neverUploadedRef) }
        report.check("deleting a never-uploaded object does not throw", firstDelete.isSuccess, "$firstDelete")
        val secondDelete = runCatching { storage.deletePhoto(neverUploadedRef) }
        report.check("a repeated delete of the same missing object is idempotent", secondDelete.isSuccess, "$secondDelete")

        val uploadThenDeleteRef = storage.uploadPhoto(itemId, onePixelPng)
        storage.deletePhoto(uploadThenDeleteRef)
        val doubleDelete = runCatching { storage.deletePhoto(uploadThenDeleteRef) }
        report.check(
            "deleting an already-deleted (real) object is idempotent too",
            doubleDelete.isSuccess,
            "$doubleDelete",
        )

        // --- cross-user denial: a second user is denied, not merely "not found" ---------
        val ownerRef = storage.uploadPhoto(itemId, onePixelPng)

        auth.signOut()
        delay(SETTLE_MS)
        report.expectSuccess("client B sign-up", auth.signUp(emailB, PASSWORD))
        delay(SETTLE_MS)
        val uidB = authBridge.currentUser()?.uid
        if (uidB == null) {
            report.fail("preconditions", "client B sign-up did not resolve a uid")
            return report.render()
        }
        report.check("client A and client B are genuinely different users", uidA != uidB, "uidA=$uidA uidB=$uidB")

        val readDenial = runCatching { storage.loadPhoto(ownerRef) }
        report.check(
            "cross-user read of client A's object is denied, not falsely reported as missing",
            readDenial.isFailure && readDenial.isGenuineDenial(),
            "$readDenial",
        )

        val writeDenial = runCatching { uploadDataRaw(storageBridge, ownerRef, onePixelPng) }
        report.check(
            "cross-user write to client A's exact photoRef is denied",
            writeDenial.isFailure && writeDenial.isGenuineDenial(),
            "$writeDenial",
        )

        val deleteDenial = runCatching { deleteObjectRaw(storageBridge, ownerRef) }
        report.check(
            "cross-user delete of client A's exact photoRef is denied",
            deleteDenial.isFailure && deleteDenial.isGenuineDenial(),
            "$deleteDenial",
        )

        // Sanity: sign back in as the owner and confirm the object is provably untouched
        // by any of the three denied cross-user attempts above - proves the denials above
        // were real denials, not a same-path 404 both users would have hit.
        auth.signOut()
        delay(SETTLE_MS)
        report.expectSuccess("client A sign-in", auth.signIn(emailA, PASSWORD))
        delay(SETTLE_MS)
        val ownerReload = storage.loadPhoto(ownerRef)
        report.check(
            "the owner's object is provably untouched by every denied cross-user attempt",
            ownerReload is PhotoContent.Bytes && ownerReload.bytes.contentEquals(onePixelPng),
            "reload=$ownerReload",
        )
        storage.deletePhoto(ownerRef)

        // --- FB-306/FB-305-NB1: replacePhoto()'s full ordering against real Firestore + ----
        // --- real Storage together, still signed in as client A from the reload above -----
        val lists = IosFirebaseListRepository()
        val items = IosFirebaseItemRepository()
        val combinedListId = lists.createList("FB-306 combined replace check", ListIcon.CART, ListColor.PRIMARY_BLUE)
        delay(SETTLE_MS)
        items.addItem(combinedListId, "Combined replace check")
        delay(SETTLE_MS)
        val combinedItemId = items.observeItems(combinedListId).first().firstOrNull { it.title == "Combined replace check" }?.id
        if (combinedItemId == null) {
            report.fail("combined replacePhoto preconditions", "addItem's item never appeared")
            return report.render()
        }

        val firstRef = replacePhoto(
            storage = storage,
            itemId = combinedItemId,
            oldPhotoRef = null,
            newBytes = onePixelPng,
            updateRef = { ref -> items.setPhotoRef(combinedListId, combinedItemId, ref) },
        )
        delay(SETTLE_MS)
        report.check(
            "replacePhoto's first upload persists photoRef to real Firestore",
            items.observeItem(combinedListId, combinedItemId).first()?.photoRef == firstRef,
            "photoRef=${items.observeItem(combinedListId, combinedItemId).first()?.photoRef} expected=$firstRef",
        )
        val firstLoaded = storage.loadPhoto(firstRef)
        report.check(
            "the first uploaded object is real, loadable Storage bytes",
            firstLoaded is PhotoContent.Bytes && firstLoaded.bytes.contentEquals(onePixelPng),
            "loaded=$firstLoaded",
        )

        // A distinguishable second payload so a byte-content check can tell first and
        // second objects apart, not just their refs.
        val secondBytes = onePixelPng + onePixelPng
        val secondRef = replacePhoto(
            storage = storage,
            itemId = combinedItemId,
            oldPhotoRef = firstRef,
            newBytes = secondBytes,
            updateRef = { ref -> items.setPhotoRef(combinedListId, combinedItemId, ref) },
        )
        delay(SETTLE_MS)
        report.check(
            "replacePhoto's second call persists the new photoRef to real Firestore",
            items.observeItem(combinedListId, combinedItemId).first()?.photoRef == secondRef,
            "expected=$secondRef",
        )
        report.check(
            "replacePhoto deletes the old real Storage object only after Firestore is updated",
            storage.loadPhoto(firstRef) == null,
            "",
        )
        val secondLoaded = storage.loadPhoto(secondRef)
        report.check(
            "the second uploaded object is real, loadable, and distinct from the first",
            secondLoaded is PhotoContent.Bytes && secondLoaded.bytes.contentEquals(secondBytes),
            "loaded=$secondLoaded",
        )

        storage.deletePhoto(secondRef)
        items.deleteItem(combinedListId, combinedItemId)
        auth.signOut()

        return report.render()
    }

    /** Direct, [IosPhotoStorage]-bypassing raw bridge call - mirrors Android's
     * `storageB.reference.child(ownerRef).putBytes(...)` in `PhotoStorageEmulatorIntegrationTest`,
     * which also reaches under the production adapter to attempt a write at an *existing*
     * `photoRef` (something [IosPhotoStorage.uploadPhoto] itself never does - it always
     * mints a brand-new ref). */
    private suspend fun uploadDataRaw(bridge: IosFirebaseStorageBridge, photoRef: String, bytes: ByteArray) {
        val data = bytes.toNSData()
        suspendCancellableCoroutine<Unit> { continuation ->
            bridge.uploadData(photoRef, data) { error ->
                if (error != null) continuation.resumeWithException(PhotoStorageIosException(error)) else continuation.resume(Unit)
            }
        }
    }

    private suspend fun deleteObjectRaw(bridge: IosFirebaseStorageBridge, photoRef: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            bridge.deleteObject(photoRef) { error ->
                if (error != null) continuation.resumeWithException(PhotoStorageIosException(error)) else continuation.resume(Unit)
            }
        }
    }

    /** A real denial: explicitly not [isStorageObjectNotFound] - which would let "absent"
     * masquerade as "denied" and pass this assertion for the wrong reason - and specifically
     * [isStorageUnauthorized], the Storage SDK's own denial code. */
    private fun Result<*>.isGenuineDenial(): Boolean {
        val error = (exceptionOrNull() as? PhotoStorageIosException)?.error ?: return false
        return !error.isStorageObjectNotFound() && error.isStorageUnauthorized()
    }

    private class Report {
        private val lines = mutableListOf<String>()
        private var failures = 0

        fun pass(name: String, detail: String = "") {
            lines += "PASS  $name${if (detail.isEmpty()) "" else " ($detail)"}"
        }

        fun fail(name: String, detail: String) {
            failures++
            lines += "FAIL  $name ($detail)"
        }

        fun check(name: String, condition: Boolean, detail: String) {
            if (condition) pass(name) else fail(name, detail)
        }

        fun expectSuccess(name: String, result: AuthResult) {
            check(name, result is AuthResult.Success, "result=$result")
        }

        fun render(): String {
            val header = if (failures == 0) {
                "FB-305 iOS Storage integration check: ALL CHECKS PASSED"
            } else {
                "FB-305 iOS Storage integration check: $failures CHECK(S) FAILED"
            }
            return (listOf(header) + lines + listOf("FB-305 END")).joinToString("\n")
        }
    }
}
