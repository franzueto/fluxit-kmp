package dev.franzueto.fluxit.shared.domain.model

/**
 * The eight icons a user can pick for a list from the Create List icon
 * picker. Domain owns this enum (Phase 04 §2 supersedes ADR-006c's original
 * data-depends-on-designsystem direction): a list "is a cart" is a product
 * fact. The design system consumes this enum to map each ref to a concrete
 * `ImageVector` from the icon-generator output (Phase 02 ADR-005a).
 *
 * The enum *name* is the on-disk form; the data layer's `IconNameAdapter`
 * round-trips between the enum and that string. Renames here are
 * schema-affecting (ADR-006).
 *
 * Subset of the full icon catalogue — the design system ships ~25 icons,
 * but only these eight are user-pickable as a list identity. Other icons
 * (search, bell, trash, etc.) are UI chrome and don't appear here.
 */
public enum class FluxItIconRef {
    CART,
    HOME,
    BRIEFCASE,
    PLANE,
    FORK_KNIFE,
    DUMBBELL,
    STAR,
    MORE,
}
