package dev.franzueto.fluxit.platform.config

import dev.franzueto.fluxit.shared.domain.port.ConfigKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DefaultConfigProviderTest {
    @Test
    fun staged_feature_flags_are_off_in_v1() {
        assertFalse(DefaultConfigProvider.get(ConfigKey.CalendarEnabled))
        assertFalse(DefaultConfigProvider.get(ConfigKey.StarredEnabled))
    }

    @Test
    fun tuning_keys_resolve_to_their_adr_defaults() {
        assertEquals(2048, DefaultConfigProvider.get(ConfigKey.PhotoMaxDimension))
        assertEquals(0.85f, DefaultConfigProvider.get(ConfigKey.PhotoReencodeQuality))
        assertEquals(365, DefaultConfigProvider.get(ConfigKey.ReminderMaxFutureDays))
    }
}
