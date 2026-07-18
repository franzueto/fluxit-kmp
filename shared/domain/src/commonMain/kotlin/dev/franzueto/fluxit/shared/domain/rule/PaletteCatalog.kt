package dev.franzueto.fluxit.shared.domain.rule

import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef

public object PaletteCatalog {
    /** Available list-color choices, in declaration order. */
    public val colors: List<ColorToken> = ColorToken.entries

    /** Available list-icon choices, in declaration order. */
    public val icons: List<FluxItIconRef> = FluxItIconRef.entries
}
