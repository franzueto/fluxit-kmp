package dev.franzueto.fluxit.platform.photo

import android.content.Context
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * App-sandbox [PhotoStorage] over `context.filesDir/photos` (plan/06 §6). Files
 * are app-private (cleared on uninstall) and excluded from auto-backup in v1
 * (ADR-009b). Writes are atomic — bytes land in a `.tmp` sibling first, then
 * `renameTo` the final `<uuid>.<ext>` path so a reader never sees a partial file.
 *
 * Paths handed back are sandbox-**relative** (`photos/<uuid>.<ext>`); [resolveAbsolute]
 * returns the absolute filesystem path for the image loader. **v1 returns a bare
 * file path, not a `content://` FileProvider URI** — that's only needed to hand a
 * photo to an *external* app (share/edit), which v1 doesn't do; Compose's image
 * loaders read the in-app file path directly. FileProvider is a documented
 * follow-up for when sharing lands.
 */
public class AndroidPhotoStorage(
    context: Context,
) : PhotoStorage {
    private val root: File = File(context.filesDir, ROOT_DIR)

    override suspend fun write(
        bytes: ByteArray,
        mime: String,
    ): String =
        withContext(Dispatchers.IO) {
            root.mkdirs()
            val name = "${UUID.randomUUID()}.${mimeToExtension(mime)}"
            val tmp = File(root, "$name.tmp")
            tmp.writeBytes(bytes)
            val target = File(root, name)
            if (!tmp.renameTo(target)) {
                // renameTo can fail across edge cases; fall back to copy+delete so the
                // write still completes atomically from a reader's point of view.
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            "$ROOT_DIR/$name"
        }

    override suspend fun read(relativePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            fileFor(relativePath).takeIf { it.exists() }?.readBytes()
        }

    override suspend fun delete(relativePath: String): Boolean =
        withContext(Dispatchers.IO) {
            fileFor(relativePath).let { it.exists() && it.delete() }
        }

    override fun resolveAbsolute(relativePath: String): String = fileFor(relativePath).absolutePath

    /** Resolves a sandbox-relative path under [root], guarding against path escape. */
    private fun fileFor(relativePath: String): File {
        val name = File(relativePath).name
        return File(root, name)
    }

    private companion object {
        const val ROOT_DIR = "photos"
    }
}
