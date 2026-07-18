package dev.franzueto.fluxit.shared.domain.model

/**
 * The enum *name* is the on-disk form (e.g. `"PRIMARY_BLUE"`); the data
 * layer's `ColorTokenAdapter` round-trips between the enum and that string
 * so renames in this file would be a schema-affecting change (ADR-006).
 */
public enum class ColorToken {
    PRIMARY_BLUE,
    ACCENT_ROSE,
    ACCENT_EMERALD,
    ACCENT_ORANGE,
    ACCENT_INDIGO,
    ACCENT_SKY,
}
