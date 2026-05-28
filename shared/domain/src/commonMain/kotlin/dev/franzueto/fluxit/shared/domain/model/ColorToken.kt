package dev.franzueto.fluxit.shared.domain.model

/**
 * The six accent colours a user can pick for a list. Mirrors the swatch
 * palette in the Create List screen (Phase 02 design system).
 *
 * Owned by domain rather than the design system because "this list is rose"
 * is a product fact, not a rendering detail (see Phase 04 §2 and ADR-006c
 * supersedure note). The design system *consumes* this enum to map each
 * variant onto a concrete `Color` from the generated token palette.
 *
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
