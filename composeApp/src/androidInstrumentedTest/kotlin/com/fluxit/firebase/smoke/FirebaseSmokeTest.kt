package com.fluxit.firebase.smoke

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FB-008 TEMPORARY Phase 0 smoke harness (Android leg).
 *
 * ## Why this exists
 *
 * Nothing in this repository has ever proven that the Firebase runtime actually
 * initializes and works on a real Android target: `FB-006` proved compilation and
 * `FB-007` proved iOS linking, but no app had been launched anywhere. This test is
 * the Android half of the two-platform smoke matrix required by Phase 0's exit
 * criterion, and it runs against the **live development Firebase project**, not the
 * emulator suite (`fluxit.firebase.emulator.enabled` stays `false`).
 *
 * ## Why it is an instrumented test rather than in-app code
 *
 * An instrumented test runs in the application process and therefore boots the real
 * [com.fluxit.FluxItApplication], which calls `AndroidFirebaseInitializer.initialize`.
 * That gives the runtime-initialization proof the plan asks for **without adding a
 * single line to the shipped application**: nothing here is compiled into the APK,
 * no production repository is touched, Room is untouched, and the Koin graph is
 * unchanged. `FB-009` removes this harness by deleting
 * `composeApp/src/androidInstrumentedTest/` and the `androidInstrumentedTest`
 * dependency block plus `testInstrumentationRunner` line in `composeApp/build.gradle.kts`.
 *
 * ## Live cross-user denial preflight (hard requirement, `FB-008`)
 *
 * `MAN-002` (the owner-only Rules deploy) is a user self-report that no agent has
 * ever verified. Before this harness writes any real data it creates two throwaway
 * accounts and, signed in as B, attempts read **and** write against A's Firestore and
 * Storage paths; every attempt must be rejected. A second denial sweep runs later
 * against resources that genuinely exist, which removes the "denied or merely
 * absent?" ambiguity from the preflight's negative results.
 *
 * ## Credential hygiene
 *
 * Both accounts are created here, at run time, with [SecureRandom]-generated
 * passwords that are never logged, never persisted and never leave the process. Both
 * accounts, and every document and object they create, are deleted in [cleanUp].
 * No service-account key, token or pre-existing user credential is used.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseSmokeTest {

    private companion object {
        const val TAG = "FluxItSmoke"
        const val OP_TIMEOUT_SECONDS = 60L
        const val LISTEN_TIMEOUT_SECONDS = 60L

        /** Firestore path shape from `firestore.rules`: `users/{uid}/lists/{listId}`. */
        fun listDoc(firestore: FirebaseFirestore, uid: String, listId: String): DocumentReference =
            firestore.collection("users").document(uid)
                .collection("lists").document(listId)

        /** Storage path shape from `storage.rules`: `users/{uid}/items/{itemId}/{photoId}`. */
        fun photoRef(storage: FirebaseStorage, uid: String, itemId: String, photoId: String): StorageReference =
            storage.reference
                .child("users").child(uid)
                .child("items").child(itemId)
                .child(photoId)
    }

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val storage: FirebaseStorage get() = FirebaseStorage.getInstance()

    private val runId = UUID.randomUUID().toString().substring(0, 8)
    private val emailA = "fluxit-smoke-a-$runId@example.com"
    private val emailB = "fluxit-smoke-b-$runId@example.com"
    private val passwordA = randomPassword()
    private val passwordB = randomPassword()

    private val results = mutableListOf<String>()

    @Test
    fun androidFirebaseSmokeMatrix() {
        try {
            val uidA: String
            val uidB: String

            // --- 0. Runtime initialization -----------------------------------
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = FirebaseApp.getInstance()
            assertEquals("[DEFAULT]", app.name, "default FirebaseApp should be the one the app initialized")
            assertTrue(app.options.projectId!!.isNotBlank(), "resolved projectId must be non-blank")
            assertEquals("com.fluxit", context.packageName)
            record("init", "PASS", "default FirebaseApp present in the app process (FluxItApplication ran)")

            // --- 1/2. Two throwaway accounts ---------------------------------
            uidA = signUp(emailA, passwordA)
            record("auth-uid-A", "PASS", "sign-up returned a non-blank uid")
            uidB = signUp(emailB, passwordB)
            record("auth-uid-B", "PASS", "second sign-up returned a distinct non-blank uid")
            assertTrue(uidA.isNotBlank() && uidB.isNotBlank())
            assertTrue(uidA != uidB, "the two throwaway accounts must have distinct uids")

            // --- 3. LIVE cross-user denial preflight, before any real write ---
            // Signed in as B (createUser leaves B as the current user).
            assertEquals(uidB, auth.currentUser!!.uid)

            expectFirestoreDenied("preflight-firestore-read") {
                Tasks.await(listDoc(firestore, uidA, "smoke-$runId").get(), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            expectFirestoreDenied("preflight-firestore-write") {
                Tasks.await(
                    listDoc(firestore, uidA, "smoke-$runId").set(mapOf("intruder" to true)),
                    OP_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            }
            expectStorageDenied("preflight-storage-read") {
                Tasks.await(
                    photoRef(storage, uidA, "smoke-$runId", "photo.png").getBytes(1_000_000L),
                    OP_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            }
            expectStorageDenied("preflight-storage-write") {
                Tasks.await(
                    photoRef(storage, uidA, "smoke-$runId", "photo.png").putBytes(smallPng()),
                    OP_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            }

            // --- 4. Sign in as the owner -------------------------------------
            signIn(emailA, passwordA)
            assertEquals(uidA, auth.currentUser!!.uid)

            val doc = listDoc(firestore, uidA, "smoke-$runId")
            val photo = photoRef(storage, uidA, "smoke-$runId", "photo.png")

            // --- 5. Firestore write + listen ---------------------------------
            val onlineToken = "online-$runId"
            val onlineLatch = CountDownLatch(1)
            var registration: ListenerRegistration? = null
            try {
                registration = doc.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "listener error", error)
                        return@addSnapshotListener
                    }
                    if (snapshot?.getString("marker") == onlineToken && !snapshot.metadata.hasPendingWrites()) {
                        onlineLatch.countDown()
                    }
                }
                Tasks.await(
                    doc.set(mapOf("marker" to onlineToken, "createdBy" to "FB-008-android")),
                    OP_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
                assertTrue(
                    onlineLatch.await(LISTEN_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "snapshot listener never observed the server-acknowledged write"
                )
                record("firestore-write-listen", "PASS", "server-acknowledged snapshot observed by the listener")
            } finally {
                registration?.remove()
            }

            // --- 6. Offline write, observed after reconnect ------------------
            val offlineToken = "offline-$runId"
            val pendingLatch = CountDownLatch(1)
            val ackedLatch = CountDownLatch(1)
            var offlineRegistration: ListenerRegistration? = null
            try {
                offlineRegistration = doc.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    if (snapshot.getString("offlineMarker") != offlineToken) return@addSnapshotListener
                    if (snapshot.metadata.hasPendingWrites()) {
                        pendingLatch.countDown()
                    } else if (!snapshot.metadata.isFromCache) {
                        ackedLatch.countDown()
                    }
                }
                Tasks.await(firestore.disableNetwork(), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Deliberately NOT awaited: while the network is disabled Firestore does
                // not complete this Task until the server acknowledges the write. The
                // local effect is observed through the snapshot listener instead.
                val offlineWrite = doc.set(mapOf("offlineMarker" to offlineToken), SetOptions.merge())
                assertTrue(
                    pendingLatch.await(LISTEN_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "offline write was never visible locally with hasPendingWrites=true"
                )
                assertTrue(!offlineWrite.isComplete, "write must still be unacknowledged while offline")
                record("firestore-offline-write", "PASS", "local snapshot with hasPendingWrites=true while offline")

                Tasks.await(firestore.enableNetwork(), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertTrue(
                    ackedLatch.await(LISTEN_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "offline write was never server-acknowledged after reconnect"
                )
                Tasks.await(offlineWrite, OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                record("firestore-reconnect", "PASS", "same write observed server-acknowledged after reconnect")
            } finally {
                offlineRegistration?.remove()
            }

            // --- 7. Storage upload + download --------------------------------
            val png = smallPng()
            Tasks.await(photo.putBytes(png), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            record("storage-upload", "PASS", "${png.size}-byte PNG uploaded to the owner path")
            val downloaded = Tasks.await(photo.getBytes(1_000_000L), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertContentEquals(png, downloaded, "downloaded bytes must match the uploaded bytes")
            record("storage-download", "PASS", "downloaded bytes byte-identical to the upload")

            // --- 8. Denial sweep against resources that actually exist -------
            signIn(emailB, passwordB)
            assertEquals(uidB, auth.currentUser!!.uid)
            expectFirestoreDenied("live-denied-read-existing-doc") {
                Tasks.await(doc.get(), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            expectStorageDenied("live-denied-read-existing-object") {
                Tasks.await(photo.getBytes(1_000_000L), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }

            // --- 9. Storage delete, verified ---------------------------------
            signIn(emailA, passwordA)
            Tasks.await(photo.delete(), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val afterDelete = runCatching {
                Tasks.await(photo.getBytes(1_000_000L), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.exceptionOrNull()
            val deleteCode = (afterDelete?.cause as? StorageException)?.errorCode
            assertEquals(
                StorageException.ERROR_OBJECT_NOT_FOUND,
                deleteCode,
                "after delete the owner's own download must fail with object-not-found, got $afterDelete"
            )
            record("storage-delete", "PASS", "owner download after delete fails with ERROR_OBJECT_NOT_FOUND")
        } finally {
            cleanUp()
            Log.i(TAG, "===== FB-008 ANDROID SMOKE MATRIX (run $runId) =====")
            results.forEach { Log.i(TAG, it) }
            Log.i(TAG, "===== end matrix =====")
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun signUp(email: String, password: String): String {
        val result = awaitTask(auth.createUserWithEmailAndPassword(email, password))
        return result.user!!.uid
    }

    private fun signIn(email: String, password: String) {
        awaitTask(auth.signInWithEmailAndPassword(email, password))
    }

    private fun <T> awaitTask(task: Task<T>): T =
        Tasks.await(task, OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun expectFirestoreDenied(step: String, block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
            ?: fail("$step: expected PERMISSION_DENIED but the operation succeeded")
        val cause = (thrown as? ExecutionException)?.cause ?: thrown
        val code = (cause as? FirebaseFirestoreException)?.code
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            code,
            "$step: expected PERMISSION_DENIED, got $cause"
        )
        record(step, "DENIED (expected)", "FirebaseFirestoreException PERMISSION_DENIED")
    }

    private fun expectStorageDenied(step: String, block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
            ?: fail("$step: expected a Storage authorization failure but the operation succeeded")
        val cause = (thrown as? ExecutionException)?.cause ?: thrown
        val code = (cause as? StorageException)?.errorCode
        // ERROR_OBJECT_NOT_FOUND would mean the Rules ALLOWED the access and the object
        // simply was not there, which is exactly the false pass this preflight exists to
        // rule out, so it is asserted against explicitly.
        assertEquals(
            StorageException.ERROR_NOT_AUTHORIZED,
            code,
            "$step: expected ERROR_NOT_AUTHORIZED (-13021), got $cause"
        )
        record(step, "DENIED (expected)", "StorageException ERROR_NOT_AUTHORIZED")
    }

    private fun record(step: String, outcome: String, detail: String) {
        results += "%-36s %-18s %s".format(step, outcome, detail)
    }

    private fun randomPassword(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#\$%"
        val random = SecureRandom()
        return (1..24).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun smallPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(0x2E, 0x7D, 0x32))
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" }
        bitmap.recycle()
        return out.toByteArray()
    }

    /**
     * Best-effort teardown. Deletes the harness's own document, object and both
     * throwaway accounts so a run leaves nothing behind in the development project.
     * Failures here are logged, never thrown, so they cannot mask a real result.
     */
    private fun cleanUp() {
        runCatching { Tasks.await(firestore.enableNetwork(), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        listOf(Triple("A", emailA, passwordA), Triple("B", emailB, passwordB)).forEach { (label, email, password) ->
            runCatching {
                signIn(email, password)
                val uid = auth.currentUser!!.uid
                runCatching {
                    Tasks.await(
                        photoRef(storage, uid, "smoke-$runId", "photo.png").delete(),
                        OP_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                }
                runCatching {
                    Tasks.await(
                        listDoc(firestore, uid, "smoke-$runId").delete(),
                        OP_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                }
                Tasks.await(auth.currentUser!!.delete(), OP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                record("cleanup-account-$label", "PASS", "account and its data removed")
            }.onFailure { Log.w(TAG, "cleanup failed for a harness account", it) }
        }
        runCatching { auth.signOut() }
    }
}
