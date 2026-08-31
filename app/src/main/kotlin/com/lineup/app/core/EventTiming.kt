package com.lineup.app.core

import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The date/time arithmetic behind the calendar intent, kept free of Android types so it can
 * be unit tested.
 */
data class EventTiming(
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    /** The zone the provider should record the event in. */
    val timeZoneId: String,
)

object EventTimingCalculator {

    const val DEFAULT_DURATION_HOURS = 2L

    /** Returns null when the draft has no date, in which case the calendar app picks defaults. */
    fun compute(draft: EventDraft, zone: ZoneId): EventTiming? {
        val date = draft.date ?: return null
        val start = draft.startTime
            ?: return EventTiming(
                beginMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                endMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                allDay = true,
                timeZoneId = zone.id,
            )

        val begin = date.atTime(start)
        var end = draft.endTime?.let { endTime ->
            val sameDay = date.atTime(endTime)
            when {
                sameDay.isAfter(begin) -> sameDay
                // A degenerate range is bad OCR, not a 24 hour event.
                endTime == start -> begin.plusHours(DEFAULT_DURATION_HOURS)
                // "10 PM - 1 AM" genuinely runs past midnight.
                else -> date.plusDays(1).atTime(endTime)
            }
        } ?: begin.plusHours(DEFAULT_DURATION_HOURS)
        if (!end.isAfter(begin)) end = begin.plusHours(DEFAULT_DURATION_HOURS)

        return EventTiming(
            beginMillis = begin.atZone(zone).toInstant().toEpochMilli(),
            endMillis = end.atZone(zone).toInstant().toEpochMilli(),
            allDay = false,
            timeZoneId = zone.id,
        )
    }

    /**
     * The same timing, adjusted for a direct write to the calendar provider.
     *
     * CalendarContract requires an all-day event to sit at midnight UTC; the intent path
     * wants local midnight instead, so the two callers cannot share one answer.
     */
    fun computeForProvider(draft: EventDraft, zone: ZoneId): EventTiming? {
        val timing = compute(draft, zone) ?: return null
        if (!timing.allDay) return timing
        val date = draft.date ?: return null
        return EventTiming(
            beginMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endMillis = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            allDay = true,
            timeZoneId = "UTC",
        )
    }
}
