package com.fluxit.data

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.firebase.list.CurrentUidProvider
import com.fluxit.firebase.list.FirebaseAuthCurrentUidProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPhotoPicker : PhotoPicker {

    private var launchPicker: (() -> Unit)? = null
    private var pending: CompletableDeferred<ByteArray?>? = null

    /** Must be called from the host activity's onCreate, before it is started. */
    fun register(activity: ComponentActivity) {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            val bytes = uri?.let {
                runCatching {
                    activity.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }
                }.getOrNull()
            }
            pending?.complete(bytes)
            pending = null
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
        return deferred.await()
    }
}

/**
 * Interim local-file [PhotoStorage] stub (`FB-302`). It produces/consumes correctly-shaped
 * `photoRef` strings (PLAN-006/PLAN-007, via [FirebaseSchema.photoRef]) using the real
 * signed-in uid ([CurrentUidProvider] - the same production seam
 * `AndroidFirebaseItemRepository` already uses for Firestore paths - so a `photoRef` minted
 * here is already exactly the string a real Cloud Storage adapter would need), but it still
 * stores bytes on local disk rather than in Cloud Storage. Real upload/download/delete
 * against Firebase Storage is `FB-304`, deliberately out of this task's scope.
 */
class AndroidPhotoStorage(
    private val context: Context,
    private val currentUid: CurrentUidProvider = FirebaseAuthCurrentUidProvider(),
) : PhotoStorage {

    private val photosDir: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val photoRef = FirebaseSchema.photoRef(currentUid.currentUid(), itemId, newPhotoId())
            localFile(photoRef).writeBytes(bytes)
            photoRef
        }

    override suspend fun loadPhoto(photoRef: String): PhotoContent? = withContext(Dispatchers.IO) {
        val file = localFile(photoRef)
        if (file.exists()) PhotoContent.Loadable(file.absolutePath) else null
    }

    override suspend fun deletePhoto(photoRef: String) {
        // File.delete() returns false (no exception) for an already-missing file, which is
        // exactly the idempotency PhotoStorage.deletePhoto's contract requires.
        withContext(Dispatchers.IO) { localFile(photoRef).delete() }
    }

    /**
     * Maps a `photoRef` 1:1 onto a local cache file name by flattening its path separators.
     * `FB-304` replaces this whole class with a real Cloud Storage object addressed by the
     * same `photoRef`; nothing else needs to change when it does.
     */
    private fun localFile(photoRef: String): File = File(photosDir, photoRef.replace('/', '_'))
}
