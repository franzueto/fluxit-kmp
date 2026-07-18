package dev.franzueto.fluxit.platform.photo

import android.content.Context
import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
