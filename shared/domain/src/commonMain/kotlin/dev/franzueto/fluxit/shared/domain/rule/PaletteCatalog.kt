package dev.franzueto.fluxit.shared.domain.rule

import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef

/**
 * Single source of truth for the Create-List picker's available
 * `ColorToken` swatches and `FluxItIconRef` icon chips (Phase 04 §8).
 *
 * Wrapping `ColorToken.entries` / `FluxItIconRef.entries` here gives
 * the "what colors / icons can the user pick?" question one place to
 * be answered — UI code in Phase 09 (Create List) and Phase 08 (List
 * Detail header chip) both go through this catalog rather than each
 * surface deriving the list independently. v1 surfaces the full enum;
 * a v2 feature flag (per-tier picker subsets, A/B tests) would refine
 * the catalog without touching the enum itself.
 */
public object PaletteCatalog {
    /** Available list-color choices, in declaration order. */
    public val colors: List<ColorToken> = ColorToken.entries

    /** Available list-icon choices, in declaration order. */
    public val icons: List<FluxItIconRef> = FluxItIconRef.entries
}
