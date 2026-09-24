package com.fluxit.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.config.FirebaseEmulatorConfig
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.firebase.list.CurrentUidProvider
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `FB-304` Android integration checks: the real Firebase Android Storage SDK, driven through
 * [AndroidPhotoStorage], against a real Storage emulator (loading the same `storage.rules`
 * deployed to the development project - `FB-005`/`MAN-002`) - proving the ledger's acceptance
 * criterion, "Android integration/manual checks pass under Storage Rules".
 *
 * Deliberately *not* against the live development project: every object this suite writes
 * lives only in the emulator's in-memory store. Authentication is real (against the Auth
 * emulator, via dedicated secondary [FirebaseApp] instances), because `storage.rules` is
 * owner-only and needs a real ID token carrying a real `uid` to authorize anything.
 *
 * Two genuinely *different* signed-up users (client A, client B - distinct emails, distinct
 * uids), not two connections as the same user the way `CrossClientItemEmulatorIntegrationTest`
 * uses "cross-client" - this suite needs the FB-008-shaped cross-*user* denial check the task
 * brief calls for, not multi-device same-user behavior (already covered elsewhere).
 *
 * [ownerCanUploadRealDecodedAndResizedBytesThenLoadThenDeleteThem] deliberately runs
 * [preparePhotoForUpload] with its *real* default `readDimensions`/`resize` parameters (the
 * real `android.graphics.BitmapFactory`-backed [readImageDimensions]/[resizeImage] actuals in
 * `ImageTransform.android.kt`) against a source image large enough to force the resize branch.
 * This project has no Robolectric, so `testDebugUnitTest` cannot exercise those actuals
 * (`FB-303-NB1`); an `androidInstrumentedTest` runs on a real Android runtime where
 * `BitmapFactory` genuinely works, so this is the first test anywhere in the suite that
 * exercises them for real, end to end, through a real upload/download round trip.
 *
 * Prerequisites, because this test cannot provision them itself:
 *
 * 1. an Android emulator/device with the host reachable at `10.0.2.2` (a stock AVD is);
 * 2. the Auth and Storage emulators running on the host, from the repository root:
 *    `firebase/node_modules/.bin/firebase emulators:start --only auth,storage --project demo-fluxit`
 *    (ports come from `firebase.json`; the client side reads [FirebaseEmulatorConfig], the
 *    FB-006 single source of truth), loading this repository's real `storage.rules`.
 *
 * Run with: `./gradlew :composeApp:connectedDebugAndroidTest`, or non-interactively via
 * `firebase/node_modules/.bin/firebase --project demo-fluxit emulators:exec --only auth,storage
 * "./gradlew :composeApp:connectedDebugAndroidTest"` from the repository root.
 */
@RunWith(AndroidJUnit4::class)
class PhotoStorageEmulatorIntegrationTest {

    private lateinit var authA: FirebaseAuth
    private lateinit var authB: FirebaseAuth
    private lateinit var storageA: FirebaseStorage
    private lateinit var storageB: FirebaseStorage
    private lateinit var uidA: String
    private lateinit var uidB: String
    private lateinit var clientA: AndroidPhotoStorage
    private lateinit var clientB: AndroidPhotoStorage

    @Before
    fun connectTwoIndependentClientsAsTwoDifferentUsers(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (builtAuthA, builtStorageA) = buildClientApp(context, "fb304-photo-client-a")
        val (builtAuthB, builtStorageB) = buildClientApp(context, "fb304-photo-client-b")
        authA = builtAuthA
        authB = builtAuthB
        storageA = builtStorageA
        storageB = builtStorageB

        authA.createUserWithEmailAndPassword(uniqueEmail("a"), PASSWORD).awaitResult()
        uidA = requireNotNull(authA.currentUser?.uid) { "client A sign-up did not resolve a uid" }
        authB.createUserWithEmailAndPassword(uniqueEmail("b"), PASSWORD).awaitResult()
        uidB = requireNotNull(authB.currentUser?.uid) { "client B sign-up did not resolve a uid" }
        check(uidA != uidB) { "client A and client B must be genuinely different users for a cross-user denial check" }

        clientA = AndroidPhotoStorage(storageA, CurrentUidProvider { uidA })
        clientB = AndroidPhotoStorage(storageB, CurrentUidProvider { uidB })
    }

    @After
    fun signOutBothClients() {
        authA.signOut()
        authB.signOut()
    }

    // --- owner: real upload/load/delete round trip, at the exact photoRef shape -------

    @Test
    fun ownerCanUploadThenLoadThenDeleteTheirOwnPhotoAtTheExactPhotoRefShape(): Unit = runBlocking {
        val itemId = UUID.randomUUID().toString()
        val bytes = samplePngBytes()

        val photoRef = clientA.uploadPhoto(itemId, bytes)
        assertEquals("users/$uidA/items/$itemId", photoRef.substringBeforeLast('/'))
        assertEquals(itemId, FirebaseSchema.itemIdFromPhotoRef(photoRef))

        val loaded = assertIs<PhotoContent.Bytes>(clientA.loadPhoto(photoRef))
        assertContentEquals(bytes, loaded.bytes, "downloaded bytes must be byte-identical to what was uploaded")

        clientA.deletePhoto(photoRef)
        assertNull(clientA.loadPhoto(photoRef), "the object must be gone immediately after delete")
    }

    /** `FB-303-NB1`: see this file's class KDoc for why this test exists. */
    @Test
    fun ownerCanUploadRealDecodedAndResizedBytesThenLoadThenDeleteThem(): Unit = runBlocking {
        val itemId = UUID.randomUUID().toString()
        val source = largeSourcePngBytes()
        val sourceDimensions = requireNotNull(readImageDimensions(source)) { "test fixture must itself be real, decodable image bytes" }
        assertTrue(
            maxOf(sourceDimensions.widthPx, sourceDimensions.heightPx) > PhotoPolicy.MAX_DIMENSION_PX,
            "test fixture must be large enough to force the real resize branch",
        )

        // Real defaults: the real BitmapFactory-backed readImageDimensions/resizeImage
        // actuals, not fakes - this is what makes this test discharge FB-303-NB1.
        val prepared = preparePhotoForUpload(source)
        assertTrue(prepared.size <= PhotoPolicy.MAX_UPLOAD_BYTES, "prepared bytes must respect the upload ceiling")
        val preparedDimensions = requireNotNull(readImageDimensions(prepared)) { "resized output must itself decode" }
        assertTrue(maxOf(preparedDimensions.widthPx, preparedDimensions.heightPx) <= PhotoPolicy.MAX_DIMENSION_PX)

        val photoRef = clientA.uploadPhoto(itemId, prepared)
        val loaded = assertIs<PhotoContent.Bytes>(clientA.loadPhoto(photoRef))
        assertContentEquals(prepared, loaded.bytes, "the real resized bytes must round-trip through Storage byte-identically")

        clientA.deletePhoto(photoRef)
        assertNull(clientA.loadPhoto(photoRef))
    }

    // --- missing-object semantics: null on load, silent no-op on delete ---------------

    @Test
    fun loadingAMissingObjectReturnsNullInsteadOfThrowing(): Unit = runBlocking {
        val itemId = UUID.randomUUID().toString()
        val neverUploadedRef = FirebaseSchema.photoRef(uidA, itemId, newPhotoId())

        assertNull(clientA.loadPhoto(neverUploadedRef))
    }

    @Test
    fun deletingAMissingObjectIsIdempotentAndNeverThrows(): Unit = runBlocking {
        val itemId = UUID.randomUUID().toString()
        val neverUploadedRef = FirebaseSchema.photoRef(uidA, itemId, newPhotoId())

        clientA.deletePhoto(neverUploadedRef) // first call: nothing to delete
        clientA.deletePhoto(neverUploadedRef) // repeat call: must still not throw

        val realRef = clientA.uploadPhoto(itemId, samplePngBytes())
        clientA.deletePhoto(realRef) // first real delete
        clientA.deletePhoto(realRef) // delete of the now-already-deleted object: must not throw either
    }

    // --- cross-user denial: a second user is denied, not merely "not found" -----------

    @Test
    fun crossUserReadOfTheFirstUsersObjectIsDeniedNotFalselyReportedAsMissing(): Unit = runBlocking {
        val itemId = UUID.randomUUID().toString()
        val ownerRef = clientA.uploadPhoto(itemId, samplePngBytes())

        val denial = assertFailsWith<StorageException>("client B must not be able to read client A's object") {
            clientB.loadPhoto(ownerRef)
        }
        assertDenied(denial)

        // Sanity: the object genuinely exists and the owner can still read it - proves the
        // above was a real denial, not a same-path 404 both clients would have hit.
        assertIs<PhotoContent.Bytes>(clientA.loadPhoto(ownerRef))
    }

    @Test
    fun crossUserWriteAndDeleteOfTheFirstUsersObjectAreBothDenied(): Unit = runBlocking {
        val itemId = UUID.randomUUID().toString()
        val ownerRef = clientA.uploadPhoto(itemId, samplePngBytes())

        val writeDenial = assertFailsWith<StorageException>("client B must not be able to overwrite client A's object") {
            storageB.reference.child(ownerRef).putBytes(samplePngBytes()).awaitResult()
        }
        assertDenied(writeDenial)

        val deleteDenial = assertFailsWith<StorageException>("client B must not be able to delete client A's object") {
            storageB.reference.child(ownerRef).delete().awaitResult()
        }
        assertDenied(deleteDenial)

        // The owner's object must be provably untouched by either denied attempt.
        assertIs<PhotoContent.Bytes>(clientA.loadPhoto(ownerRef))
    }

    // --- helpers ------------------------------------------------------------------------

    /** A real denial: the specific code Storage Rules use for a permission failure, and
     * explicitly not [StorageException.ERROR_OBJECT_NOT_FOUND] - which would let "absent"
     * masquerade as "denied" and pass this assertion for the wrong reason. */
    private fun assertDenied(exception: StorageException) {
        assertTrue(
            exception.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND,
            "a Rules denial must not be reported as object-not-found: ${exception.errorCode}/${exception.message}",
        )
        assertEquals(
            StorageException.ERROR_NOT_AUTHORIZED,
            exception.errorCode,
            "expected the Storage SDK's own denial code, got ${exception.errorCode}: ${exception.message}",
        )
    }

    private suspend fun buildClientApp(context: android.content.Context, appName: String): Pair<FirebaseAuth, FirebaseStorage> {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == appName }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    // Throwaway values: both emulators accept any key/app id, and a
                    // `demo-` project id can never resolve to a real Firebase project.
                    .setApiKey("fb304-instrumented-test-key")
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
        val storage = FirebaseStorage.getInstance(app).apply {
            if (appName !in emulatorConfiguredAppNames) {
                useEmulator(emulatorHost(), FirebaseEmulatorConfig.STORAGE_PORT)
            }
        }
        emulatorConfiguredAppNames += appName
        return auth to storage
    }

    private fun uniqueEmail(suffix: String): String = "fb304-photo-$suffix-${UUID.randomUUID()}@example.test"

    private fun emulatorHost(): String = when (FirebaseEmulatorConfig.HOST) {
        "127.0.0.1", "localhost" -> ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS
        else -> FirebaseEmulatorConfig.HOST
    }

    /** A tiny, real, decodable PNG - deterministic solid-color pixels so byte-equality checks are stable. */
    private fun samplePngBytes(width: Int = 4, height: Int = 4): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** A real, decodable PNG whose long edge exceeds [PhotoPolicy.MAX_DIMENSION_PX], to force
     * [preparePhotoForUpload]'s real resize branch. */
    private fun largeSourcePngBytes(): ByteArray {
        val width = PhotoPolicy.MAX_DIMENSION_PX + 500
        val height = (width * 0.7).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { style = Paint.Style.FILL }
        val random = Random(304)
        repeat(60) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(random.nextInt(width).toFloat(), random.nextInt(height).toFloat(), 80f, paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        /** Throwaway passphrase for emulator-only accounts; not a credential. */
        const val PASSWORD = "fb304-emulator-only"
        const val ANDROID_EMULATOR_HOST_LOOPBACK_ALIAS = "10.0.2.2"

        /** `useEmulator` may only be called once per SDK instance; apps are reused by name across test methods. */
        val emulatorConfiguredAppNames = mutableSetOf<String>()
    }
}

/**
 * Local `Task.await()`, mirroring the identically shaped private helper in
 * `AndroidFirebaseItemRepository.kt`/the FB-206 emulator suites (this module has no
 * `kotlinx-coroutines-play-services` dependency).
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val exception = task.exception
        when {
            exception != null -> continuation.resumeWithException(exception)
            task.isCanceled -> continuation.cancel(CancellationException("Firebase Storage task cancelled"))
            else -> continuation.resume(task.result)
        }
    }
}
