package dev.franzueto.fluxit.shared.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

// Pins the enum *names* — ADR-006 schema-affecting changes if any of these
// shift. The data layer's adapters use `Enum.name` ↔ String round-trips,
// so renaming a constant silently breaks every row that stored the old name.
// If you need to rename, write an ADR + a SQLDelight migration that
// rewrites the affected rows.
class EnumStorageContractTest {
    @Test
    fun colorToken_names_are_stable() {
        assertEquals(
            listOf(
                "PRIMARY_BLUE",
                "ACCENT_ROSE",
                "ACCENT_EMERALD",
                "ACCENT_ORANGE",
                "ACCENT_INDIGO",
                "ACCENT_SKY",
            ),
            ColorToken.entries.map { it.name },
        )
    }

    @Test
    fun fluxItIconRef_names_are_stable() {
        assertEquals(
            listOf(
                "CART",
                "HOME",
                "BRIEFCASE",
                "PLANE",
                "FORK_KNIFE",
                "DUMBBELL",
                "STAR",
                "MORE",
            ),
            FluxItIconRef.entries.map { it.name },
        )
    }

    @Test
    fun reminderOwnerType_names_are_stable() {
        assertEquals(listOf("LIST", "ITEM"), ReminderOwnerType.entries.map { it.name })
    }
}
