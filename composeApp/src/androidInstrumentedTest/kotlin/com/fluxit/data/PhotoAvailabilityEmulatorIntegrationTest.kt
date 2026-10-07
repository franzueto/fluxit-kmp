package com.fluxit.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.firebase.item.AndroidFirebaseItemRepository
import com.fluxit.firebase.list.CurrentUidProvider
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android integration checks for the Phase 3 exit criterion: "photos remain
 * available after reinstall/sign-in on both Android and iOS, and failed replacements do
 * not orphan the item or destroy the previous photo."
 *
 * Same real Auth+Firestore+Storage emulator pattern `PhotoStorageEmulatorIntegrationTest`
 * and `CrossClientItemEmulatorIntegrationTest` already
 * establish - genuinely independent, separately-named [FirebaseApp] instances, never a
 * mock/fake, against the real deployed `storage.rules`/`firestore.rules` loaded by the
 * emulators.
 *
 * ### Reinstall (property 1) - design note
 * A literal uninstall/reinstall cannot be driven from inside a JUnit test process. This
 * suite instead connects the "after" side through a **brand-new, never-before-used**
 * secondary [FirebaseApp] name. The Android Firebase SDK gives every distinct app name its
 * own isolated on-disk local-persistence directory (Firestore's offline document cache,
 * Auth's token store) - so an "after" client sharing an app name with nothing that came
 * before it has exactly as little warm local state as a freshly reinstalled app has, and
 * arguably less than a same-app-name sign-out/sign-in cycle would. This is at least as strong a proof of "did not depend on local cache/DB
 * surviving" as a literal reinstall would give for this specific property.
 *
 * ### Cross-device (property 2)
 * Two independent, simultaneously-connected secondary [FirebaseApp] instances signed in
 * as the same uid - genuinely two separate SDK connections in this JVM, sharing no
 * in-memory state, synchronized only through the real emulator backends. This is the
 * Android analogue of `CrossClientItemEmulatorIntegrationTest`'s existing pattern, applied
 * to photos specifically (not yet covered anywhere else).
 *
 * ### Interrupted replace / orphan detection (properties 3 and 4)
 * `replacePhoto()`'s mandated ordering (`PhotoBridges.kt`, unmodified by this task) is
 * upload -> persist -> best-effort-delete-old. This suite reproduces exactly the failure
 * mode a killed process / dropped connection produces - the upload step commits for real
 * against the real Storage emulator, then the very next step (persisting the new
 * `photoRef` to Firestore) fails - by injecting a single throw into the `updateRef` lambda
 * `replacePhoto` is given, at the exact point between those two steps. This is one of the
 * task brief's explicitly sanctioned interruption methods ("force-kill during the
 * upload-then-persist-then-delete sequence from the `replacePhoto`"), and is
 * deterministic/reproducible where a literal process kill timed against a live network
 * call would not be. `retryPhotoOperation()` (`ItemDetailViewModel.kt`) recovers
 * from exactly this failure by calling `performReplace` again with the same cached raw
 * bytes - i.e. one more `replacePhoto()` call with the same `itemId`/`oldPhotoRef`/bytes -
 * so the second `replacePhoto()` call below is code-identical to what the real retry UI
 * does, not a re-derived approximation of it.
 *
 * Clearing the Firestore/Storage local caches on sign-out is not exercised here; the
 * session-cleanup scenarios cover it.
 */
@RunWith(AndroidJUnit4::class)
class PhotoAvailabilityEmulatorIntegrationTest {

    // --- property 1: reinstall/sign-in availability -----------------------------------

    @Test
    fun photoRemainsAvailableAfterASimulatedReinstallViaABrandNewClientWithNoSharedLocalState(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (authBefore, firestoreBefore, storageBefore) = buildClientApp(context, "photoavail-reinstall-before")
        val email = uniqueEmail("reinstall")

        authBefore.createUserWithEmailAndPassword(email, PASSWORD).awaitResult()
        val uid = requireNotNull(authBefore.currentUser?.uid) { "sign-up did not resolve a uid" }
        val itemRepoBefore = AndroidFirebaseItemRepository(firestoreBefore, CurrentUidProvider { uid })
        val photoStorageBefore = AndroidPhotoStorage(storageBefore, CurrentUidProvider { uid })

        val listId = bootstrapList(firestoreBefore, uid)
        itemRepoBefore.addItem(listId, "Reinstall check item")
        val itemId = withTimeout(TIMEOUT_MS) { itemRepoBefore.observeItems(listId).first { it.isNotEmpty() } }.single().id
        val bytes = samplePngBytes()
        val uploadedRef = replacePhoto(
            storage = photoStorageBefore,
            itemId = itemId,
            oldPhotoRef = null,
            newBytes = bytes,
            updateRef = { ref -> itemRepoBefore.setPhotoRef(listId, itemId, ref) },
        )
        authBefore.signOut()

        // "After reinstall": a brand-new secondary FirebaseApp name this test process has
        // never used before - zero shared Kotlin/JVM state and (per the class KDoc) its
        // own never-before-touched on-disk local persistence directory.
        val (authAfter, firestoreAfter, storageAfter) = buildClientApp(context, "photoavail-reinstall-after")
        authAfter.signInWithEmailAndPassword(email, PASSWORD).awaitResult()
        check(authAfter.currentUser?.uid == uid) { "reinstalled client resolved a different uid" }
        val itemRepoAfter = AndroidFirebaseItemRepository(firestoreAfter, CurrentUidProvider { uid })
        val photoStorageAfter = AndroidPhotoStorage(storageAfter, CurrentUidProvider { uid })

        val itemAfterReinstall = withTimeout(TIMEOUT_MS) { itemRepoAfter.observeItem(listId, itemId).first { it != null } }
        assertEquals(
            uploadedRef,
            itemAfterReinstall?.photoRef,
            "the item's photoRef must survive reinstall - it lives in Firestore, not any local cache/DB",
        )
        val loadedAfterReinstall = photoStorageAfter.loadPhoto(uploadedRef)
        val resolved = assertIs<PhotoContent.Bytes>(loadedAfterReinstall)
        assertContentEquals(
            bytes,
            resolved.bytes,
            "photo bytes must be byte-identical, fetched fresh from Storage with zero warm local state",
        )
    }

    // --- property 2: cross-device availability -----------------------------------------

    @Test
    fun photoUploadedFromOneClientIsVisibleFromAnIndependentSecondClientSignedInAsTheSameUser(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (authA, firestoreA, storageA) = buildClientApp(context, "photoavail-crossdevice-a")
        val (authB, firestoreB, storageB) = buildClientApp(context, "photoavail-crossdevice-b")
        val email = uniqueEmail("crossdevice")

        authA.createUserWithEmailAndPassword(email, PASSWORD).awaitResult()
        val uid = requireNotNull(authA.currentUser?.uid) { "sign-up did not resolve a uid" }
        authB.signInWithEmailAndPassword(email, PASSWORD).awaitResult()
        check(authB.currentUser?.uid == uid) { "device B resolved a different uid than device A" }

        val itemRepoA = AndroidFirebaseItemRepository(firestoreA, CurrentUidProvider { uid })
        val itemRepoB = AndroidFirebaseItemRepository(firestoreB, CurrentUidProvider { uid })
        val photoStorageA = AndroidPhotoStorage(storageA, CurrentUidProvider { uid })
        val photoStorageB = AndroidPhotoStorage(storageB, CurrentUidProvider { uid })

        val listId = bootstrapList(firestoreA, uid)
        itemRepoA.addItem(listId, "Cross-device check item")
        val itemId = withTimeout(TIMEOUT_MS) { itemRepoA.observeItems(listId).first { it.isNotEmpty() } }.single().id
        // Device B must independently see the item exists before this test proves
        // anything about the photo specifically, over its own separate SDK connection.
        withTimeout(TIMEOUT_MS) { itemRepoB.observeItem(listId, itemId).first { it != null } }

        val bytes = samplePngBytes()
        val ref = replacePhoto(
            storage = photoStorageA,
            itemId = itemId,
            oldPhotoRef = null,
            newBytes = bytes,
            updateRef = { newRef -> itemRepoA.setPhotoRef(listId, itemId, newRef) },
        )

        // Device B never touched device A's in-memory state - only the real Auth/
        // Firestore/Storage emulator backends connect them.
        val itemOnB = withTimeout(TIMEOUT_MS) { itemRepoB.observeItem(listId, itemId).first { it?.photoRef == ref } }
        assertEquals(ref, itemOnB?.photoRef)
        val loadedOnB = photoStorageB.loadPhoto(ref)
        val resolved = assertIs<PhotoContent.Bytes>(loadedOnB)
        assertContentEquals(bytes, resolved.bytes, "device B must load byte-identical bytes via Storage, not any local sharing")
    }

    // --- properties 3 and 4: interrupted replace, old-photo preservation, retry, -------
    // --- and orphan detection -----------------------------------------------------------

    @Test
    fun interruptedReplacePreservesTheOldPhotoAndRetryRecoversCleanlyLeavingOnlyASweepReclaimableOrphan(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (auth, firestore, storage) = buildClientApp(context, "photoavail-interrupted-replace")
        val email = uniqueEmail("interrupted")

        auth.createUserWithEmailAndPassword(email, PASSWORD).awaitResult()
        val uid = requireNotNull(auth.currentUser?.uid) { "sign-up did not resolve a uid" }
        val itemRepo = AndroidFirebaseItemRepository(firestore, CurrentUidProvider { uid })
        val recordingStorage = RecordingPhotoStorage(AndroidPhotoStorage(storage, CurrentUidProvider { uid }))

        val listId = bootstrapList(firestore, uid)
        itemRepo.addItem(listId, "Interrupted replace check item")
        val itemId = withTimeout(TIMEOUT_MS) { itemRepo.observeItems(listId).first { it.isNotEmpty() } }.single().id

        // Baseline: establish an old, referenced photo (P1) exactly as a normal successful
        // replace would.
        val oldBytes = samplePngBytes(color = Color.MAGENTA)
        val p1 = replacePhoto(
            storage = recordingStorage,
            itemId = itemId,
            oldPhotoRef = null,
            newBytes = oldBytes,
            updateRef = { ref -> itemRepo.setPhotoRef(listId, itemId, ref) },
        )
        assertEquals(p1, withTimeout(TIMEOUT_MS) { itemRepo.observeItem(listId, itemId).first { it?.photoRef != null } }?.photoRef)

        // Interrupted replace: the new object genuinely uploads to the real Storage
        // emulator (step 1 of replacePhoto's ordering completes for real), then the
        // process is "killed" - simulated by a single throw injected exactly at step 2,
        // before the new photoRef is ever persisted to Firestore.
        val newBytes = samplePngBytes(color = Color.CYAN)
        var failNextPersist = true
        val interruptingUpdateRef: suspend (String) -> Unit = { ref ->
            if (failNextPersist) {
                failNextPersist = false
                throw IllegalStateException("simulated interruption: killed after Storage upload, before Firestore persist")
            }
            itemRepo.setPhotoRef(listId, itemId, ref)
        }
        assertFailsWith<IllegalStateException> {
            replacePhoto(storage = recordingStorage, itemId = itemId, oldPhotoRef = p1, newBytes = newBytes, updateRef = interruptingUpdateRef)
        }
        val orphanRef = requireNotNull(recordingStorage.lastUploadedRef) { "the interrupted attempt must still have uploaded a real object" }
        assertNotEquals(p1, orphanRef, "the orphan must be a genuinely different object than the preserved old photo")

        // --- property 3: old photo preserved, not lost/corrupted -----------------------
        val afterInterruption = itemRepo.observeItem(listId, itemId).first()
        assertEquals(p1, afterInterruption?.photoRef, "an interrupted replace must leave the item pointing at the old photo, unchanged")
        val oldStillLoadable = assertIs<PhotoContent.Bytes>(recordingStorage.loadPhoto(p1))
        assertContentEquals(oldBytes, oldStillLoadable.bytes, "the old photo must be byte-identical and undamaged after the interruption")

        // --- property 4 (part 1): the orphan is real, present, and reclaimable - not a --
        // --- silent leak and not a dangling reference the UI would surface --------------
        val orphanLoaded = assertIs<PhotoContent.Bytes>(recordingStorage.loadPhoto(orphanRef))
        assertContentEquals(newBytes, orphanLoaded.bytes, "the orphaned object itself must be intact, real Storage content")
        // Not referenced by the item, so never rendered - matches the orphan-photo sweep's documented
        // "leaked but sweep-reclaimable" contract, not a UI-visible duplicate/stale photo.

        // --- property 3 (continued): RetryPhotoOperation recovers cleanly ----
        // retryPhotoOperation() re-runs performReplace(pendingReplaceBytes), i.e. one more
        // replacePhoto() call with the same itemId/oldPhotoRef/bytes - reproduced exactly.
        val p3 = replacePhoto(
            storage = recordingStorage,
            itemId = itemId,
            oldPhotoRef = p1,
            newBytes = newBytes,
            updateRef = { ref -> itemRepo.setPhotoRef(listId, itemId, ref) },
        )
        assertNotEquals(
            orphanRef,
            p3,
            "retry mints a brand-new object rather than resuming/reusing the earlier orphan - the documented retry semantics",
        )
        val afterRetry = withTimeout(TIMEOUT_MS) { itemRepo.observeItem(listId, itemId).first { it?.photoRef == p3 } }
        assertEquals(p3, afterRetry?.photoRef)
        val newLoaded = assertIs<PhotoContent.Bytes>(recordingStorage.loadPhoto(p3))
        assertContentEquals(newBytes, newLoaded.bytes)
        assertNull(recordingStorage.loadPhoto(p1), "a successful retry's step 3 must best-effort delete the now-superseded old photo (P1)")

        // --- property 4 (part 2): no duplicate/stale photo shown; the earlier orphan is -
        // --- untouched by the retry, still exactly the kind of object a future sweep -----
        // --- reclaims, never auto-deleted or corrupted by this client -------------------
        assertEquals(p3, itemRepo.observeItem(listId, itemId).first()?.photoRef, "the item must reference exactly one photo, never two")
        val orphanStillThere = assertIs<PhotoContent.Bytes>(recordingStorage.loadPhoto(orphanRef))
        assertContentEquals(newBytes, orphanStillThere.bytes, "the orphan from the interrupted attempt must remain intact and untouched by the retry")
    }

    // --- helpers -------------------------------------------------------------------

    private suspend fun buildClientApp(context: Context, appName: String): Triple<FirebaseAuth, FirebaseFirestore, FirebaseStorage> {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == appName }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    // Throwaway values: both emulators accept any key/app id, and a
                    // `demo-` project id can never resolve to a real Firebase project.
                    .setApiKey("photoavail-instrumented-test-key")
                    .setApplicationId("1:0:android:$appName")
                    .setProjectId("demo-fluxit")
                    .setStorageBucket("demo-fluxit.appspot.com")
                    .build(),
                appName,
            )
        val auth = FirebaseAuth.getInstance(app).apply {
            if (appName !in emulatorConfiguredAppNames) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.AUTH_PORT)
            }
            signOut()
        }
        val firestore = FirebaseFirestore.getInstance(app).apply {
            if (appName !in emulatorConfiguredAppNames) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.FIRESTORE_PORT)
            }
        }
        val storage = FirebaseStorage.getInstance(app).apply {
            if (appName !in emulatorConfiguredAppNames) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.STORAGE_PORT)
            }
        }
        emulatorConfiguredAppNames += appName
        firestore.enableNetwork().awaitResult()
        return Triple(auth, firestore, storage)
    }

    /** Bootstraps a bare list document with the exact fields the counter transactions
     * read/write - same helper shape `CrossClientItemEmulatorIntegrationTest` uses. */
    private suspend fun bootstrapList(firestore: FirebaseFirestore, uid: String): String {
        val id = UUID.randomUUID().toString()
        firestore.collection("users").document(uid).collection("lists").document(id).set(
            mapOf(
                "name" to "check list",
                "icon" to "CART",
                "color" to "PRIMARY_BLUE",
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "deletedAt" to null,
                "totalItems" to 0L,
                "completedItems" to 0L,
                "schemaVersion" to 1L,
            )
        ).awaitResult()
        return id
    }

    private fun uniqueEmail(suffix: String): String = "photoavail-photo-$suffix-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    /** A tiny, real, decodable PNG - deterministic solid-color pixels so byte-equality checks are stable. */
    private fun samplePngBytes(width: Int = 4, height: Int = 4, color: Int = Color.MAGENTA): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "photoavail-emulator-only"
        const val TIMEOUT_MS = 30_000L
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per SDK instance; apps are reused by name across test methods. */
        val emulatorConfiguredAppNames = mutableSetOf<String>()
    }
}

/** Records the most recent `photoRef` [PhotoStorage.uploadPhoto] minted, so a test can
 * find/verify an orphan object left behind by an interrupted [replacePhoto] call whose
 * `updateRef` step threw before returning that ref to the caller. */
private class RecordingPhotoStorage(private val delegate: PhotoStorage) : PhotoStorage {
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

/**
 * Local `Task.await()`, mirroring the identically shaped private helper in this
 * `androidInstrumentedTest` source set's other emulator suites (this module has no
 * `kotlinx-coroutines-play-services` dependency).
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val exception = task.exception
        when {
            exception != null -> continuation.resumeWithException(exception)
            task.isCanceled -> continuation.cancel(CancellationException("Firebase task cancelled"))
            else -> continuation.resume(task.result)
        }
    }
}
