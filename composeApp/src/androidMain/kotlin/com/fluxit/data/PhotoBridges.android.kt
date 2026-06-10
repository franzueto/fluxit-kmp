package com.fluxit.data

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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

class AndroidPhotoStorage(private val context: Context) : PhotoStorage {

    private val photosDir: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    override suspend fun savePhoto(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val file = File(photosDir, "${newId()}.jpg")
        file.writeBytes(bytes)
        file.absolutePath
    }

    override suspend fun deletePhoto(path: String) {
        withContext(Dispatchers.IO) { File(path).delete() }
    }
}
