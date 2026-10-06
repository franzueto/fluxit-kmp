package com.fluxit.firebase.storage

import com.fluxit.data.IosPhotoStorage
import com.fluxit.data.PhotoContent
import com.fluxit.data.PhotoStorage
import com.fluxit.data.PhotoStorageException
import com.fluxit.data.newPhotoId
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.replacePhoto
import com.fluxit.data.toNSData
import com.fluxit.data.validatePhotoSource
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.auth.AuthResult
import com.fluxit.firebase.IosFirebaseEmulatorSettings
import com.fluxit.firebase.auth.IosAuthBridgeRegistry
import com.fluxit.firebase.auth.IosAuthRepository
import com.fluxit.firebase.item.IosFirebaseItemRepository
import com.fluxit.firebase.list.IosFirebaseListRepository
import com.fluxit.firebase.list.ListRepositoryException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * `FB-307` added three more entry points below, gated by their own separate launch
 * arguments in `FirebaseBootstrap.swift` (not folded into [run], so each can be driven
 * independently from `xcrun simctl`):
 *  - [runInterruptedReplaceCheck]: a single-process check proving old-photo preservation,
 *    orphan detection, and retry recovery for an interrupted/failed `replacePhoto()`, the
 *    same failure-injection shape `PhotoStorageEmulatorIntegrationTest`'s Android FB-307
 *    sibling test uses.
 *  - [runCrossDevicePublish] / [runCrossDeviceSubscribe]: a **two-process** pair sharing
 *    one fixed account/list/item identity, coordinated only through the real Auth/
 *    Firestore/Storage emulator backends (this app registers exactly one Swift bridge
 *    instance per process - PLAN-008/FB-206's already-documented constraint - so genuine
 *    simultaneous cross-device evidence on iOS requires two separate simulator
 *    *processes*, not two in-process connections the way the Android suite achieves it).
 *    Run publish then subscribe on two independently booted simulators for cross-device
 *    evidence, or run publish, perform a literal `xcrun simctl uninstall`+`install` on the
 *    *same* simulator, then run subscribe, for a literal (not simulated) reinstall check -
 *    both reuse this same pair rather than needing separate harnesses.
 */
object IosPhotoStorageIntegrationCheck {

    private const val PASSWORD = "fb305-emulator-only"
    private const val SETTLE_MS = 400L

    // --- FB-307 cross-device/reinstall publish-subscribe pair: fixed shared identity ---
    private const val CROSS_DEVICE_EMAIL = "fb307-crossdevice@example.com"
    private const val CROSS_DEVICE_LIST_NAME = "FB-307 cross-device check"
    private const val CROSS_DEVICE_ITEM_TITLE = "FB-307 cross-device photo"
    private const val SUBSCRIBE_TIMEOUT_MS = 20_000L

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

    /**
     * `FB-307` property 3/4: single-process check proving old-photo preservation, orphan
     * detection, and retry recovery for an interrupted/failed `replacePhoto()`. See this
     * object's class KDoc for how it complements [runCrossDevicePublish]/
     * [runCrossDeviceSubscribe].
     */
    suspend fun runInterruptedReplaceCheck(): String = try {
        runInterruptedReplaceChecked()
    } catch (throwable: Throwable) {
        "FB-307 iOS Storage interrupted-replace check: THREW ${throwable::class.simpleName}: " +
            "${throwable.diagnosticDetail()}\nFB-307 END"
    }

    /**
     * `FB-307` property 2 (and, combined with a literal `xcrun simctl uninstall`+`install`
     * between the two runs, property 1): the publish half of a two-process pair. Uploads a
     * photo under a **fixed** shared account/list/item identity and leaves everything
     * signed in and undeleted so a later [runCrossDeviceSubscribe] run - on this same
     * simulator after a literal reinstall, or on an independently booted second simulator
     * - can find it through the real Firestore/Storage emulator backends alone.
     * Idempotent: safe to re-run (signs in instead of signing up if the account already
     * exists; clears any leftover item from an earlier publish run first).
     */
    suspend fun runCrossDevicePublish(): String = try {
        runCrossDevicePublishChecked()
    } catch (throwable: Throwable) {
        "FB-307 iOS Storage cross-device PUBLISH: THREW ${throwable::class.simpleName}: " +
            "${throwable.diagnosticDetail()}\nFB-307 END"
    }

    /** `FB-307` property 2/1: the subscribe half of [runCrossDevicePublish]'s pair. Signs
     * in as the same fixed account and proves the photo `runCrossDevicePublish` uploaded
     * is visible and loadable, discovered entirely through Firestore/Storage - never any
     * state shared in-process with the publish run, which by construction cannot be the
     * same process. */
    suspend fun runCrossDeviceSubscribe(): String = try {
        runCrossDeviceSubscribeChecked()
    } catch (throwable: Throwable) {
        "FB-307 iOS Storage cross-device SUBSCRIBE: THREW ${throwable::class.simpleName}: " +
            "${throwable.diagnosticDetail()}\nFB-307 END"
    }

    private suspend fun runInterruptedReplaceChecked(): String {
        val report = Report(label = "FB-307 iOS Storage interrupted-replace check")
        if (!IosFirebaseEmulatorSettings.enabled) {
            report.fail("preconditions", "emulator mode is disabled; refusing to run against a live project")
            return report.render()
        }
        val auth = IosAuthRepository()
        val storage = RecordingIosPhotoStorage(IosPhotoStorage())
        val lists = IosFirebaseListRepository()
        val items = IosFirebaseItemRepository()
        val suffix = NSUUID().UUIDString().lowercase()
        val email = "fb307-interrupted-$suffix@example.com"

        report.expectSuccess("sign up a fresh account for this check", auth.signUp(email, PASSWORD))
        delay(SETTLE_MS)
        val listId = lists.createList("FB-307 interrupted replace check", ListIcon.CART, ListColor.PRIMARY_BLUE)
        delay(SETTLE_MS)
        items.addItem(listId, "Interrupted replace item")
        delay(SETTLE_MS)
        val itemId = items.observeItems(listId).first().firstOrNull { it.title == "Interrupted replace item" }?.id
        if (itemId == null) {
            report.fail("preconditions", "addItem's item never appeared")
            return report.render()
        }

        // Baseline: an old, referenced photo (P1), exactly as a normal successful
        // replace would leave.
        val oldBytes = onePixelPng
        val p1 = replacePhoto(
            storage = storage,
            itemId = itemId,
            oldPhotoRef = null,
            newBytes = oldBytes,
            updateRef = { ref -> items.setPhotoRef(listId, itemId, ref) },
        )
        delay(SETTLE_MS)

        // Interrupted replace: the new object genuinely uploads to the real Storage
        // emulator (step 1 completes for real), then a single throw injected exactly at
        // step 2 simulates the process being killed before the new photoRef is ever
        // persisted to Firestore - one of the task brief's explicitly sanctioned
        // interruption methods.
        val newBytes = onePixelPng + onePixelPng
        var failNextPersist = true
        val interruptingUpdateRef: suspend (String) -> Unit = { ref ->
            if (failNextPersist) {
                failNextPersist = false
                throw IllegalStateException("FB-307 simulated interruption: killed after Storage upload, before Firestore persist")
            }
            items.setPhotoRef(listId, itemId, ref)
        }
        val interrupted = runCatching {
            replacePhoto(storage = storage, itemId = itemId, oldPhotoRef = p1, newBytes = newBytes, updateRef = interruptingUpdateRef)
        }
        report.check("the interrupted replace call itself fails/propagates", interrupted.isFailure, "$interrupted")
        val orphanRef = storage.lastUploadedRef
        report.check(
            "the interrupted attempt still uploaded a real (now orphaned) object",
            orphanRef != null && orphanRef != p1,
            "orphanRef=$orphanRef",
        )
        delay(SETTLE_MS)

        // --- property 3: old photo preserved, not lost/corrupted -----------------------
        val afterInterruption = items.observeItem(listId, itemId).first()
        report.check(
            "the item still points at the old photo after the interruption",
            afterInterruption?.photoRef == p1,
            "photoRef=${afterInterruption?.photoRef}",
        )
        val oldStillLoaded = storage.loadPhoto(p1)
        report.check(
            "the old photo is still loadable and byte-identical after the interruption",
            oldStillLoaded is PhotoContent.Bytes && oldStillLoaded.bytes.contentEquals(oldBytes),
            "loaded=$oldStillLoaded",
        )

        // --- property 4 (part 1): the orphan is real, present, and reclaimable ----------
        if (orphanRef != null) {
            val orphanLoaded = storage.loadPhoto(orphanRef)
            report.check(
                "the orphaned object is real, intact Storage content (sweep-reclaimable, not a silent leak)",
                orphanLoaded is PhotoContent.Bytes && orphanLoaded.bytes.contentEquals(newBytes),
                "loaded=$orphanLoaded",
            )
        }

        // --- property 3 (continued): retry (FB-306's retryPhotoOperation, reproduced ----
        // exactly - one more replacePhoto() call with the same cached bytes) recovers ----
        val p3 = replacePhoto(
            storage = storage,
            itemId = itemId,
            oldPhotoRef = p1,
            newBytes = newBytes,
            updateRef = { ref -> items.setPhotoRef(listId, itemId, ref) },
        )
        delay(SETTLE_MS)
        report.check(
            "retry mints a brand-new object, not reusing the earlier orphan",
            orphanRef != null && p3 != orphanRef,
            "p3=$p3 orphanRef=$orphanRef",
        )
        val afterRetry = items.observeItem(listId, itemId).first()
        report.check("after a successful retry the item points at exactly the new photo", afterRetry?.photoRef == p3, "photoRef=${afterRetry?.photoRef}")
        val newLoaded = storage.loadPhoto(p3)
        report.check(
            "the new photo loads byte-identical after retry",
            newLoaded is PhotoContent.Bytes && newLoaded.bytes.contentEquals(newBytes),
            "loaded=$newLoaded",
        )
        report.check("the old photo (P1) is deleted after a successful retry's step 3", storage.loadPhoto(p1) == null, "")

        // --- property 4 (part 2): no duplicate/stale photo shown; the orphan remains ----
        // untouched by the retry -----------------------------------------------------------
        if (orphanRef != null) {
            val orphanStillThere = storage.loadPhoto(orphanRef)
            report.check(
                "the orphan from the interrupted attempt remains intact and untouched by the retry",
                orphanStillThere is PhotoContent.Bytes && orphanStillThere.bytes.contentEquals(newBytes),
                "loaded=$orphanStillThere",
            )
        }
        report.check(
            "the item references exactly one photo, never a duplicate/stale one",
            items.observeItem(listId, itemId).first()?.photoRef == p3,
            "",
        )

        storage.deletePhoto(p3)
        orphanRef?.let { runCatching { storage.deletePhoto(it) } }
        items.deleteItem(listId, itemId)
        auth.signOut()

        return report.render()
    }

    private suspend fun runCrossDevicePublishChecked(): String {
        val report = Report(label = "FB-307 iOS Storage cross-device PUBLISH")
        if (!IosFirebaseEmulatorSettings.enabled) {
            report.fail("preconditions", "emulator mode is disabled; refusing to run against a live project")
            return report.render()
        }
        val auth = IosAuthRepository()
        val storage = IosPhotoStorage()
        val lists = IosFirebaseListRepository()
        val items = IosFirebaseItemRepository()

        // Idempotent identity: sign up on the very first publish run; every later run
        // (including one after a literal reinstall of this same app) signs in instead.
        val signUp = auth.signUp(CROSS_DEVICE_EMAIL, PASSWORD)
        if (signUp is AuthResult.Success) {
            report.pass("publish: signed up the fixed cross-device account for the first time")
        } else {
            report.expectSuccess(
                "publish: sign in as the fixed cross-device account (already exists from a prior run)",
                auth.signIn(CROSS_DEVICE_EMAIL, PASSWORD),
            )
        }
        delay(SETTLE_MS)

        val existingListId = lists.observeListSummariesSnapshot().first {
            !it.isFromCache && !it.hasPendingWrites
        }.value.firstOrNull { it.list.name == CROSS_DEVICE_LIST_NAME }?.list?.id
        val listId = existingListId ?: lists.createList(CROSS_DEVICE_LIST_NAME, ListIcon.CART, ListColor.PRIMARY_BLUE)
        delay(SETTLE_MS)
        report.pass("publish: resolved a listId", "listId=$listId existedAlready=${existingListId != null}")

        // Always create a brand-new item rather than trying to find-and-reuse one left
        // by an earlier run. Reusing was tried first and found genuinely unsafe: this
        // simulator's own Firestore client can carry a locally-persisted cache from a
        // *previous* launch that still shows an item a *different* device (a separate
        // simulator process, or a subscribe run's own cleanup) already deleted server-
        // side - a single `.first()` read can return that stale local snapshot rather
        // than waiting for a fresh one, so `setPhotoRef`'s bare `update()` genuinely
        // NOT_FOUNDs against a document the server no longer has. That is correct,
        // documented Firestore/`ItemRepository` behavior (see
        // `AndroidFirebaseItemRepository`'s KDoc on `updateItem`/`setPhotoRef`'s bare-
        // `update()`-throws-NOT_FOUND asymmetry) surfacing through a genuinely stale
        // local read in this test harness - not a `PhotoStorage`/`replacePhoto()` defect,
        // so the fix belongs here, not in production code. A fresh item per run sidesteps
        // the staleness question entirely; any duplicate/stale items left by an earlier
        // run's incomplete attempt are swept up below so `runCrossDeviceSubscribe`'s
        // exact-title match stays unambiguous.
        // FB-701: UUID order does not identify the new item. Capture server-confirmed
        // preexisting IDs, then select only this write's newly observed document.
        val priorItemIds = items.observeItemsSnapshot(listId).first {
            !it.isFromCache && !it.hasPendingWrites
        }.value.map { it.id }.toSet()
        val addItemResult = runCatching { items.addItem(listId, CROSS_DEVICE_ITEM_TITLE) }
        if (addItemResult.isFailure) {
            report.fail("publish: addItem", "listId=$listId threw ${addItemResult.exceptionOrNull()}")
            return report.render()
        }
        val itemId = withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
            items.observeItemsSnapshot(listId).first { snapshot ->
                !snapshot.isFromCache && !snapshot.hasPendingWrites && snapshot.value.any {
                    it.title == CROSS_DEVICE_ITEM_TITLE && it.id !in priorItemIds
                }
            }.value.single { it.title == CROSS_DEVICE_ITEM_TITLE && it.id !in priorItemIds }.id
        }
        if (itemId == null) {
            report.fail("publish preconditions", "addItem's item never appeared under listId=$listId")
            return report.render()
        }
        report.pass("publish: resolved a fresh itemId", "itemId=$itemId")

        val replaceResult = runCatching {
            replacePhoto(
                storage = storage,
                itemId = itemId,
                oldPhotoRef = null,
                newBytes = onePixelPng,
                updateRef = { r -> items.setPhotoRef(listId, itemId, r) },
            )
        }
        if (replaceResult.isFailure) {
            report.fail(
                "publish: replacePhoto",
                "listId=$listId itemId=$itemId threw ${replaceResult.exceptionOrNull()}",
            )
            return report.render()
        }
        val ref = replaceResult.getOrThrow()
        delay(SETTLE_MS)
        report.check(
            "publish: the photo is real, loadable Storage bytes right after upload",
            (storage.loadPhoto(ref) as? PhotoContent.Bytes)?.bytes?.contentEquals(onePixelPng) == true,
            "ref=$ref",
        )

        // Sweep any other same-titled item left by an earlier run's incomplete attempt,
        // so `runCrossDeviceSubscribe`'s exact-title match finds exactly one candidate.
        // `deleteItem` is safe/idempotent against an already-gone document (transactional
        // read-first, mirrors the Android repository's documented no-op-on-missing
        // behavior), so this is safe even against further staleness.
        items.observeItemsSnapshot(listId).first { !it.isFromCache && !it.hasPendingWrites }.value
            .filter { it.title == CROSS_DEVICE_ITEM_TITLE && it.id != itemId }
            .forEach { stale ->
                stale.photoRef?.let { staleRef -> runCatching { storage.deletePhoto(staleRef) } }
                runCatching { items.deleteItem(listId, stale.id) }
            }

        report.pass(
            "publish: left signed in with data ready for a subscribe run to find",
            "listId=$listId itemId=$itemId photoRef=$ref",
        )
        // Deliberately does NOT sign out or delete anything - a subsequent subscribe run
        // (on this same simulator after a literal reinstall, or on an independently
        // booted second simulator) must find this state through the real Firestore/
        // Storage emulator backends alone, not through any leftover in-process state.
        return report.render()
    }

    private suspend fun runCrossDeviceSubscribeChecked(): String {
        val report = Report(label = "FB-307 iOS Storage cross-device SUBSCRIBE")
        if (!IosFirebaseEmulatorSettings.enabled) {
            report.fail("preconditions", "emulator mode is disabled; refusing to run against a live project")
            return report.render()
        }
        val auth = IosAuthRepository()
        val storage = IosPhotoStorage()
        val lists = IosFirebaseListRepository()
        val items = IosFirebaseItemRepository()

        // A genuinely independent run (this process knows nothing a publish run did,
        // except the fixed shared credentials) signing in as the same account a publish
        // run already used - exactly what a second device, or a reinstalled app signing
        // back in, does.
        report.expectSuccess("subscribe: sign in as the fixed cross-device account", auth.signIn(CROSS_DEVICE_EMAIL, PASSWORD))
        delay(SETTLE_MS)

        val listId = withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
            lists.observeListSummariesSnapshot().first { snapshot ->
                !snapshot.isFromCache && !snapshot.hasPendingWrites &&
                    snapshot.value.any { it.list.name == CROSS_DEVICE_LIST_NAME }
            }.value.first { it.list.name == CROSS_DEVICE_LIST_NAME }.list.id
        }
        if (listId == null) {
            report.fail("subscribe", "no list named '$CROSS_DEVICE_LIST_NAME' was found - did a publish run happen first?")
            return report.render()
        }
        report.pass("subscribe: found the list a publish run created", "listId=$listId")

        val item = withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
            items.observeItemsSnapshot(listId).first { snapshot ->
                !snapshot.isFromCache && !snapshot.hasPendingWrites &&
                    snapshot.value.any { it.title == CROSS_DEVICE_ITEM_TITLE && it.photoRef != null }
            }.value.first { it.title == CROSS_DEVICE_ITEM_TITLE && it.photoRef != null }
        }
        if (item?.photoRef == null) {
            report.fail("subscribe", "no item with a photoRef was found under listId=$listId")
            return report.render()
        }
        report.pass("subscribe: found the item and its photoRef via Firestore alone", "itemId=${item.id} photoRef=${item.photoRef}")

        val photoRef = item.photoRef
        val loaded = storage.loadPhoto(photoRef)
        report.check(
            "subscribe: the photo bytes load via Storage and are byte-identical to what publish uploaded",
            loaded is PhotoContent.Bytes && loaded.bytes.contentEquals(onePixelPng),
            "loaded=$loaded",
        )

        // Clean up so the next publish/subscribe pair starts from a clean, known state.
        runCatching { storage.deletePhoto(photoRef) }
        items.deleteItem(listId, item.id)
        auth.signOut()

        return report.render()
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
        val mimeType = validatePhotoSource(bytes).mimeType
        suspendCancellableCoroutine<Unit> { continuation ->
            bridge.uploadData(photoRef, data, mimeType) { error ->
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

    /**
     * A real denial: explicitly not "object not found" - which would let "absent" masquerade
     * as "denied" and pass this assertion for the wrong reason.
     *
     * `FB-403`: [readDenial] above goes through [IosPhotoStorage.loadPhoto], which now wraps a
     * genuine Storage denial in [PhotoStorageException] carrying FB-401's neutral
     * `ApplicationError` (discharging `FB-401-NB1`/`FB-401-NB2`) rather than the raw
     * [PhotoStorageIosException]/[NSError] - checked here via
     * [RepositoryErrorCode.FORBIDDEN]. [writeDenial]/[deleteDenial] deliberately bypass
     * [IosPhotoStorage] via [uploadDataRaw]/[deleteObjectRaw] (mirroring Android's
     * `storageB.reference` raw-SDK cross-user check), so they still throw the raw
     * [PhotoStorageIosException] and are checked the original way.
     */
    private fun Result<*>.isGenuineDenial(): Boolean = when (val failure = exceptionOrNull()) {
        is PhotoStorageException -> failure.error.code == RepositoryErrorCode.FORBIDDEN
        is PhotoStorageIosException -> !failure.error.isStorageObjectNotFound() && failure.error.isStorageUnauthorized()
        else -> false
    }

    /** `FB-307` diagnostic-only helper: [ListRepositoryException]/[PhotoStorageException]
     * never carry a [Throwable.message] (they wrap a neutral
     * [com.fluxit.data.remote.ApplicationError] in their own `error` field instead), so the
     * bare `THREW ...: null` a plain `.message` read produces on these three new entry points'
     * catch blocks is uninformative - this surfaces the actual error code/detail instead. */
    private fun Throwable.diagnosticDetail(): String = when (this) {
        is ListRepositoryException -> "error=$error"
        is PhotoStorageException -> "error=$error"
        else -> "$message"
    }

    /** `FB-307`: records the most recent `photoRef` [PhotoStorage.uploadPhoto] minted, so
     * [runInterruptedReplaceChecked] can find/verify an orphan object left behind by an
     * interrupted [replacePhoto] call whose `updateRef` step threw before returning that
     * ref to the caller - the iOS analogue of the Android suite's `RecordingPhotoStorage`. */
    private class RecordingIosPhotoStorage(private val delegate: PhotoStorage) : PhotoStorage {
        var lastUploadedRef: String? = null
            private set

        override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String {
            val ref = delegate.uploadPhoto(itemId, bytes)
            lastUploadedRef = ref
            return ref
        }

        override suspend fun loadPhoto(photoRef: String): PhotoContent? = delegate.loadPhoto(photoRef)

        override suspend fun deletePhoto(photoRef: String) = delegate.deletePhoto(photoRef)
    }

    /** `FB-307`: [label] defaults to the original `FB-305` text so [run]'s already-verified
     * output is unchanged byte-for-byte; the three new `FB-307` entry points pass their own
     * distinct label so their console output is identifiable instead of misleadingly
     * reusing the `FB-305` header. */
    private class Report(private val label: String = "FB-305 iOS Storage integration check") {
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
                "$label: ALL CHECKS PASSED"
            } else {
                "$label: $failures CHECK(S) FAILED"
            }
            return (listOf(header) + lines + listOf("${label.substringBefore(' ')} END")).joinToString("\n")
        }
    }
}
