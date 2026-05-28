package dev.franzueto.fluxit.shared.domain.rule

import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import kotlin.test.Test
import kotlin.test.assertEquals

class PaletteCatalogTest {
    @Test
    fun colors_exposes_all_color_tokens_in_declaration_order() {
        assertEquals(ColorToken.entries.toList(), PaletteCatalog.colors)
    }

    @Test
    fun icons_exposes_all_fluxit_icons_in_declaration_order() {
        assertEquals(FluxItIconRef.entries.toList(), PaletteCatalog.icons)
    }

    @Test
    fun colors_matches_phase_02_swatch_count() {
        // Mirrors the Create List screen's 6-swatch palette (Phase 02
        // design system); change to this number is intentional and
        // requires a designsystem-side picker update.
        assertEquals(6, PaletteCatalog.colors.size)
    }

    @Test
    fun icons_matches_phase_02_icon_chip_count() {
        // The 8 icon-picker choices from the Create List screen.
        assertEquals(8, PaletteCatalog.icons.size)
    }
}
