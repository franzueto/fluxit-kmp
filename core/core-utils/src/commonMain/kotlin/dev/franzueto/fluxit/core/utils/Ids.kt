package dev.franzueto.fluxit.core.utils

/**
 * The actual on each platform delegates to the platform CSPRNG-backed UUID
 * generator (JVM `java.util.UUID`, Foundation `NSUUID`). Tests inject a fake
 * via [IdGenerator] rather than calling this directly.
 */
public expect fun newId(): String

/**
 * Function-typed seam so call sites depend on an injectable abstraction rather
 * than the top-level [newId]. Production binding is `::newId`; tests pass a
 * deterministic counter returning UUID-v4-shaped strings.
 */
public fun interface IdGenerator {
    public fun newId(): String

    public companion object {
        public val System: IdGenerator = IdGenerator { newId() }
    }
}
