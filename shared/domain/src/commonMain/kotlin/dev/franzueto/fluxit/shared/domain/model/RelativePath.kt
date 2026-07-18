package dev.franzueto.fluxit.shared.domain.model

import kotlin.jvm.JvmInline

/**
 * The value is non-blank by `init` guard. Path-shape validation (no
 * leading `/`, no `..` traversal, etc.) is the `PhotoStorage` impl's
 * responsibility at the data/platform boundary — this type only enforces
 * the invariant cheap enough to live in a value class.
 */
@JvmInline
public value class RelativePath(
    public val raw: String,
) {
    init {
        require(raw.isNotBlank()) { "RelativePath must not be blank" }
    }
}
