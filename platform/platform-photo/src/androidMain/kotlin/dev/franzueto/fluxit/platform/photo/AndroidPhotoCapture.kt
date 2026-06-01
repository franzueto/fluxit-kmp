package dev.franzueto.fluxit.platform.photo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.port.CaptureError
import dev.franzueto.fluxit.shared.domain.port.CapturedPhoto
import dev.franzueto.fluxit.shared.domain.port.PhotoCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Camera / photo-library [PhotoCapture] over the ActivityResult API + the §7
 * host-holder. Launching needs a live Activity registry, supplied through
 * [ActivityResultRegistryProvider]; [capture]/[pickFromLibrary] suspend until one
 * is present (up to [registryTimeoutMs] → [CaptureError.Unknown]) then drive the
 * system camera / picker via a continuation.
 *
 * The system camera handles its own permission, so no CAMERA runtime grant is
 * needed; the captured full-resolution image is written to a cache temp file
 * exposed through a [FileProvider] (`${packageName}.fileprovider`), read back as
 * bytes, then deleted. Library picks return a `content://` URI read via the
 * `ContentResolver`. Re-encoding happens above this port in the repository.
 *
 * **Manual-QA only** (plan/06 scope): no instrumented test drives the system UI.
 */
public class AndroidPhotoCapture(
    private val context: Context,
    private val registryProvider: ActivityResultRegistryProvider,
    private val registryTimeoutMs: Long = DEFAULT_REGISTRY_TIMEOUT_MS,
) : PhotoCapture {
    override suspend fun capture(): Outcome<CapturedPhoto, CaptureError> {
        val registry = awaitRegistry() ?: return Outcome.Err(CaptureError.Unknown(null))
        val tempFile = File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")
        val uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
        val result =
            runCatching {
                when (launch(registry, "capture", ActivityResultContracts.TakePicture(), uri)) {
                    null, false -> Outcome.Err(CaptureError.UserCancelled)
                    true -> readBytes(uri) { context.contentResolver.openInputStream(uri) }
                }
            }.getOrElse { Outcome.Err(CaptureError.Unknown(it)) }
        tempFile.delete()
        return result
    }

    override suspend fun pickFromLibrary(): Outcome<CapturedPhoto, CaptureError> {
        val registry = awaitRegistry() ?: return Outcome.Err(CaptureError.Unknown(null))
        return runCatching {
            val picked =
                launch(
                    registry,
                    "library",
                    ActivityResultContracts.PickVisualMedia(),
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            if (picked == null) {
                Outcome.Err(CaptureError.UserCancelled)
            } else {
                readBytes(picked) { context.contentResolver.openInputStream(picked) }
            }
        }.getOrElse { Outcome.Err(CaptureError.Unknown(it)) }
    }

    private suspend fun awaitRegistry(): ActivityResultRegistry? =
        withTimeoutOrNull(registryTimeoutMs) {
            registryProvider.current.first { it != null }
        }

    /** Registers [contract], launches it with [input], and suspends for the result. */
    private suspend fun <I, O> launch(
        registry: ActivityResultRegistry,
        keyPrefix: String,
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        input: I,
    ): O =
        suspendCancellableCoroutine { cont ->
            val key = "photo-$keyPrefix-${UUID.randomUUID()}"
            lateinit var launcher: androidx.activity.result.ActivityResultLauncher<I>
            launcher =
                registry.register(key, contract) { result ->
                    launcher.unregister()
                    cont.resume(result)
                }
            cont.invokeOnCancellation { launcher.unregister() }
            launcher.launch(input)
        }

    private suspend fun readBytes(
        uri: Uri,
        open: () -> java.io.InputStream?,
    ): Outcome<CapturedPhoto, CaptureError> =
        withContext(Dispatchers.IO) {
            val bytes = open()?.use { it.readBytes() } ?: return@withContext Outcome.Err(CaptureError.Unknown(null))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            Outcome.Ok(
                CapturedPhoto(
                    bytes = bytes,
                    mime = mime,
                    widthPx = bounds.outWidth.coerceAtLeast(0),
                    heightPx = bounds.outHeight.coerceAtLeast(0),
                ),
            )
        }

    private companion object {
        const val DEFAULT_REGISTRY_TIMEOUT_MS = 5_000L
    }
}
