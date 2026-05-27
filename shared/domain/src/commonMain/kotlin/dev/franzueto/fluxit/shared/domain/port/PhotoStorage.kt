package dev.franzueto.fluxit.shared.domain.port

/**
 * File-system side of photo persistence (Phase 03 §7). Declared in
 * `:shared:domain`; production impl ships in `:platform:platform-photo`
 * (Phase 06) on top of CameraX/PHPicker captures and an app-sandbox
 * directory root. Re-encoding to JPEG q=0.85 max-dim 2048 (resolved §12
 * row 4) happens above this port — the port only sees the final bytes.
 *
 * Paths are always **relative** to the app sandbox photo root. Absolute
 * paths leak out of this port only via [resolveAbsolute], called by the
 * UI image loader.
 */
public interface PhotoStorage {
    /** Writes [bytes] under a fresh path; returns the sandbox-relative path. */
    public suspend fun write(
        bytes: ByteArray,
        mime: String,
    ): String

    /** Reads file bytes by relative path; null when the file is missing. */
    public suspend fun read(relativePath: String): ByteArray?

    /** Deletes the file at [relativePath]; returns true if removal actually happened. */
    public suspend fun delete(relativePath: String): Boolean

    /** Resolves a sandbox-relative path to a platform-absolute path for image loading. */
    public fun resolveAbsolute(relativePath: String): String
}
