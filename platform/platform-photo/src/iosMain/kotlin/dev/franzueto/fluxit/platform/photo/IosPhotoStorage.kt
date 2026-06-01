package dev.franzueto.fluxit.platform.photo

import dev.franzueto.fluxit.shared.domain.port.PhotoStorage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * `FileManager`-backed [PhotoStorage] rooted at `applicationSupport/photos`
 * (plan/06 §6). Unlike Android (ADR-009b), iOS photos are **left in the default
 * iCloud backup** — an iCloud restore brings the user's pictures back, the only
 * "sync" v1 effectively offers (plan/06 §10). Paths are sandbox-relative
 * (`photos/<uuid>.<ext>`); [resolveAbsolute] returns the absolute file path.
 *
 * Built (not run) by the iOS-Sim gate; round-trip behaviour is verified by the
 * Robolectric Android twin + manual device QA.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosPhotoStorage : PhotoStorage {
    private val rootPath: String by lazy {
        val base =
            NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
                true,
            ).first() as String
        "$base/$ROOT_DIR".also {
            NSFileManager.defaultManager.createDirectoryAtPath(
                it,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
    }

    override suspend fun write(
        bytes: ByteArray,
        mime: String,
    ): String =
        withContext(Dispatchers.Default) {
            val name = "${NSUUID().UUIDString()}.${mimeToExtension(mime)}"
            bytes.toNSData().writeToFile("$rootPath/$name", atomically = true)
            "$ROOT_DIR/$name"
        }

    override suspend fun read(relativePath: String): ByteArray? =
        withContext(Dispatchers.Default) {
            val data: NSData? = NSData.dataWithContentsOfFile(absolutePathFor(relativePath))
            data?.toByteArray()
        }

    override suspend fun delete(relativePath: String): Boolean =
        withContext(Dispatchers.Default) {
            val path = absolutePathFor(relativePath)
            val manager = NSFileManager.defaultManager
            if (!manager.fileExistsAtPath(path)) return@withContext false
            manager.removeItemAtPath(path, error = null)
        }

    override fun resolveAbsolute(relativePath: String): String = absolutePathFor(relativePath)

    /** Resolves a sandbox-relative path under [rootPath], guarding against escape. */
    private fun absolutePathFor(relativePath: String): String {
        val name = NSURL.fileURLWithPath(relativePath).lastPathComponent ?: relativePath
        return "$rootPath/$name"
    }

    private companion object {
        const val ROOT_DIR = "photos"
    }
}
