package dev.franzueto.fluxit.shared.domain.port

import kotlin.concurrent.Volatile

public class FakePhotoStorage : PhotoStorage {
    private val files = mutableMapOf<String, ByteArray>()

    @Volatile
    private var counter: Int = 0

    public val storedPaths: Set<String> get() = files.keys.toSet()

    public fun exists(relativePath: String): Boolean = relativePath in files

    override suspend fun write(
        bytes: ByteArray,
        mime: String,
    ): String {
        val path = "photos/${++counter}.bin"
        files[path] = bytes.copyOf()
        return path
    }

    override suspend fun read(relativePath: String): ByteArray? = files[relativePath]?.copyOf()

    override suspend fun delete(relativePath: String): Boolean = files.remove(relativePath) != null

    override fun resolveAbsolute(relativePath: String): String = "/fake-sandbox/$relativePath"
}
