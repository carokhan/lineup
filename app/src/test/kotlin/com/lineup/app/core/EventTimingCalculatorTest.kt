package com.lineup.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class EventTimingCalculatorTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val date = LocalDate.of(2026, 8, 28)

    private fun timing(start: LocalTime?, end: LocalTime? = null) =
        EventTimingCalculator.compute(
            EventDraft(title = "X", date = date, startTime = start, endTime = end),
            zone,
        )

    @Test
    fun `no date means no timing extras at all`() {
        assertNull(EventTimingCalculator.compute(EventDraft(title = "X"), zone))
    }

    @Test
    fun `a date with no time becomes an all day event`() {
        val result = timing(start = null)!!
        assertTrue(result.allDay)
        assertEquals(
            Duration.ofDays(1).toMillis(),
            result.endMillis - result.beginMillis,
        )
    }

    @Test
    fun `a start with no end gets a two hour default`() {
        val result = timing(LocalTime.of(19, 0))!!
        assertFalse(result.allDay)
        assertEquals(Duration.ofHours(2).toMillis(), result.endMillis - result.beginMillis)
    }

    @Test
    fun `an explicit end is respected`() {
        val result = timing(LocalTime.of(18, 30), LocalTime.of(20, 0))!!
        assertEquals(Duration.ofMinutes(90).toMillis(), result.endMillis - result.beginMillis)
    }

    @Test
    fun `an end before the start rolls onto the next day`() {
        val result = timing(LocalTime.of(22, 0), LocalTime.of(1, 0))!!
        assertEquals(Duration.ofHours(3).toMillis(), result.endMillis - result.beginMillis)
    }

    @Test
    fun `an end equal to the start falls back to the default duration`() {
        val result = timing(LocalTime.of(20, 0), LocalTime.of(20, 0))!!
        assertEquals(Duration.ofHours(2).toMillis(), result.endMillis - result.beginMillis)
    }

    @Test
    fun `an all day event is written at utc midnight for the provider`() {
        // CalendarContract requires all-day events at midnight UTC, not local midnight.
        val draft = EventDraft(title = "X", date = date)
        val intentTiming = EventTimingCalculator.compute(draft, zone)!!
        val providerTiming = EventTimingCalculator.computeForProvider(draft, zone)!!

        assertTrue(providerTiming.allDay)
        assertEquals("UTC", providerTiming.timeZoneId)
        assertEquals(
            date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            providerTiming.beginMillis,
        )
        assertEquals(
            Duration.ofDays(1).toMillis(),
            providerTiming.endMillis - providerTiming.beginMillis,
        )
        // The intent path keeps local midnight, so the two must not be the same instant.
        assertEquals(zone.id, intentTiming.timeZoneId)
        assertTrue(intentTiming.beginMillis != providerTiming.beginMillis)
    }

    @Test
    fun `a timed event is identical on both paths`() {
        val draft = EventDraft(title = "X", date = date, startTime = LocalTime.of(19, 0))
        assertEquals(
            EventTimingCalculator.compute(draft, zone),
            EventTimingCalculator.computeForProvider(draft, zone),
        )
    }

    @Test
    fun `a dateless draft has no provider timing either`() {
        assertNull(EventTimingCalculator.computeForProvider(EventDraft(title = "X"), zone))
    }

    @Test
    fun `times are anchored to the supplied zone`() {
        val result = timing(LocalTime.of(19, 0))!!
        val expected = date.atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, result.beginMillis)
    }
}
