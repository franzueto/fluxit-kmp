package dev.franzueto.fluxit.shared.domain.model

import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceRuleTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun none_roundtrips() {
        val encoded = json.encodeToString(RecurrenceRule.serializer(), RecurrenceRule.None)
        assertEquals(RecurrenceRule.None, json.decodeFromString(RecurrenceRule.serializer(), encoded))
    }

    @Test
    fun daily_roundtrips() {
        val encoded = json.encodeToString(RecurrenceRule.serializer(), RecurrenceRule.Daily)
        assertEquals(RecurrenceRule.Daily, json.decodeFromString(RecurrenceRule.serializer(), encoded))
    }

    @Test
    fun weekly_roundtrips_with_day_set() {
        val rule: RecurrenceRule =
            RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        val encoded = json.encodeToString(RecurrenceRule.serializer(), rule)
        assertEquals(rule, json.decodeFromString(RecurrenceRule.serializer(), encoded))
    }

    @Test
    fun monthly_roundtrips() {
        val rule: RecurrenceRule = RecurrenceRule.Monthly(dayOfMonth = 15)
        val encoded = json.encodeToString(RecurrenceRule.serializer(), rule)
        assertEquals(rule, json.decodeFromString(RecurrenceRule.serializer(), encoded))
    }
}
