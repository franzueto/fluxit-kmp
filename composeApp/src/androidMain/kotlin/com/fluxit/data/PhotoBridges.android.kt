package com.fluxit.data

import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.FirebaseAuthCurrentUidProvider
import com.fluxit.firebase.storage.toApplicationError
import com.google.android.gms.tasks.Task
import com.google.firebase.storage.StorageTask
import com.fluxit.firebase.session.AndroidStorageSessionTasks
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidPhotoPicker : PhotoPicker {

    private var launchPicker: (() -> Unit)? = null
    private var pending: CompletableDeferred<ByteArray?>? = null

    /** Must be called from the host activity's onCreate, before it is started. */
    fun register(activity: ComponentActivity) {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            val request = pending ?: return@registerForActivityResult
            val bytes = uri?.let {
                runCatching {
                    activity.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }
                }.getOrNull()
            }
            request.complete(bytes)
            if (pending === request) pending = null
        }
        launchPicker = {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    override suspend fun pickPhoto(): ByteArray? {
        val launch = launchPicker ?: return null
        val deferred = CompletableDeferred<ByteArray?>()
        pending = deferred
        launch()
        return try { deferred.await() } finally { if (pending === deferred) pending = null }
    }
}

/**
 * Real Cloud Storage-backed [PhotoStorage]. Every object is addressed by the exact `photoRef` string
 * [FirebaseSchema.photoRef] already produces (`users/{uid}/items/{itemId}/{photoId}`) -
 * this class never constructs or parses that shape itself, matching the contract's
 * documented boundary. The deployed owner-only `storage.rules` gate every
 * call below; this class does not, and must not, work around them.
 *
 * Uid resolution reuses [CurrentUidProvider]/[FirebaseAuthCurrentUidProvider] exactly as
 * [com.fluxit.firebase.item.AndroidFirebaseItemRepository] does for Firestore paths -
 * resolved fresh per call, never cached, same Phase 1 constraint.
 *
 * [loadPhoto] downloads [PhotoContent.Bytes], rendered by
 * `com.fluxit.ui.components.decodeImageBytes`. Downloads reuse
 * [PhotoPolicy.MAX_UPLOAD_BYTES] as their bound, matching upload preparation.
 *
 * ### Idempotent delete / missing-object semantics
 * [loadPhoto] and [deletePhoto] both treat Firebase Storage's
 * [StorageException.ERROR_OBJECT_NOT_FOUND] as the documented "missing object" case
 * ([PhotoStorage.loadPhoto] returns `null`; [PhotoStorage.deletePhoto] is a silent
 * no-op) rather than letting it escape as a thrown exception - any other
 * [StorageException] (e.g. a genuine permission denial) still propagates, but
 * wrapped in [PhotoStorageException] via the already-tested
 * `com.fluxit.firebase.storage.StorageException.toApplicationError()` mapping
 * rather than as a raw `StorageException` instance. [uploadPhoto] deliberately swallows nothing: a failed
 * upload must propagate so `replacePhoto`'s safe-replace ordering (`PhotoBridges.kt`, unmodified
 * by this task) leaves the old photo untouched, per its documented failure semantics.
 * supplies MIME metadata from the validated byte signature because the generated
 * photo ID has no file extension for the Storage SDK to infer a type from.
 */
class AndroidPhotoStorage(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val currentUid: CurrentUidProvider = FirebaseAuthCurrentUidProvider(),
    private val photoIdFactory: () -> String = ::newPhotoId,
) : PhotoStorage {

    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String {
        val photoRef = FirebaseSchema.photoRef(currentUid.currentUid(), itemId, photoIdFactory())
        val mimeType = validatePhotoSource(bytes).mimeType
        try {
            val metadata = StorageMetadata.Builder().setContentType(mimeType).build()
            storage.reference.child(photoRef).putBytes(bytes, metadata).also(AndroidStorageSessionTasks::track).awaitResult()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: StorageException) {
            throw PhotoStorageException(failure.toApplicationError())
        }
        return photoRef
    }

    override suspend fun loadPhoto(photoRef: String): PhotoContent? = try {
        var bytes: ByteArray? = null
        val download = storage.reference.child(photoRef).getStream { _, stream ->
            bytes = readBoundedPhotoBytes(stream, MAX_DOWNLOAD_BYTES)
        }
        download.also(AndroidStorageSessionTasks::track).awaitResult()
        PhotoContent.Bytes(checkNotNull(bytes))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (missing: StorageException) {
        if (missing.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) null
        else throw PhotoStorageException(missing.toApplicationError())
    }

    override suspend fun deletePhoto(photoRef: String) {
        try {
            storage.reference.child(photoRef).delete().awaitResult()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (missing: StorageException) {
            if (missing.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) {
                throw PhotoStorageException(missing.toApplicationError())
            }
        }
    }

    private companion object {
        /**
         * Defensive ceiling passed to `getBytes`, which requires an explicit max size to
         * avoid an unbounded in-memory download. Reuses [PhotoPolicy.MAX_UPLOAD_BYTES]
         * (5 MB) rather than inventing a second, independent size constant: every object
         * this class itself ever writes is already at or under that ceiling (
         * enforces it before [uploadPhoto] is ever called), so it is also a correct upper
         * bound for anything this class should ever need to download back.
         */
        val MAX_DOWNLOAD_BYTES: Long = PhotoPolicy.MAX_UPLOAD_BYTES.toLong()
    }
}

/**
 * Local `Task.await()`, mirroring the identically shaped private helper in
 * `AndroidFirebaseItemRepository.kt`/the `androidInstrumentedTest` emulator suites (this
 * module has no `kotlinx-coroutines-play-services` dependency). Kept local rather than
 * shared, per that file's own stated rationale for not widening an existing helper's
 * visibility across an unrelated package boundary.
 */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    if (this is StorageTask<*>) continuation.invokeOnCancellation { cancel() }
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        val exception = task.exception
        when {
            exception != null -> continuation.resumeWithException(exception)
            task.isCanceled -> continuation.cancel(CancellationException("Firebase Storage task cancelled"))
            else -> continuation.resume(task.result)
        }
    }
}
