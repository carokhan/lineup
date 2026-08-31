package com.lineup.app.core

import java.time.LocalDate
import java.time.LocalTime

/**
 * How much the parser trusts a single extracted field.
 *
 * Deliberately coarse: the only decision it drives is "can the user tap through" vs
 * "the user should look at this".
 */
enum class Confidence {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    val needsReview: Boolean get() = this < MEDIUM
}

data class FieldConfidence(
    val title: Confidence = Confidence.NONE,
    val date: Confidence = Confidence.NONE,
    val startTime: Confidence = Confidence.NONE,
    val endTime: Confidence = Confidence.NONE,
    val location: Confidence = Confidence.NONE,
) {
    /** The fields that actually matter for a usable calendar entry. */
    val overall: Confidence
        get() = minOf(title, date, startTime)
}

/**
 * Everything the app knows about a candidate event. Every field is nullable on purpose:
 * a missing field means "not found", never "guessed".
 */
data class EventDraft(
    val title: String? = null,
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val location: String? = null,
    val description: String? = null,
    val confidence: FieldConfidence = FieldConfidence(),
) {
    val isBlank: Boolean
        get() = title.isNullOrBlank() && date == null && startTime == null && location.isNullOrBlank()

    companion object {
        val EMPTY = EventDraft()
    }
}
