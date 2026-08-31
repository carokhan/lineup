package com.lineup.app.parser

import com.lineup.app.core.Confidence
import java.time.DayOfWeek
import java.time.LocalDate

internal data class DateCandidate(
    val date: LocalDate,
    val confidence: Confidence,
    val lineIndex: Int,
    val range: IntRange,
    val hadExplicitYear: Boolean,
    val kind: Kind,
) {
    enum class Kind { MONTH_NAME, NUMERIC, RELATIVE, WEEKDAY }
}

/**
 * Pulls every plausible date out of the OCR lines. Ordering/selection is left to the parser;
 * this only reports what it saw and how sure it is.
 */
internal object DateExtractor {

    private const val M = Patterns.MONTH

    private val MONTH_DAY = Regex(
        """\b($M)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:\s*,?\s*(\d{4}|\d{2})(?![\d:]))?""",
        RegexOption.IGNORE_CASE,
    )

    private val DAY_MONTH = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($M)\.?\b(?:\s*,?\s*(\d{4}|\d{2})(?![\d:]))?""",
        RegexOption.IGNORE_CASE,
    )

    private val NUMERIC = Regex("""(?<![\d/.])(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?(?![\d/])""")

    /** "8.28.2026", "8-28-26" - the same separator twice, so a time range cannot match. */
    private val NUMERIC_DOTTED =
        Regex("""(?<![\d.\-/])(\d{1,2})([.\-])(\d{1,2})\2(\d{2,4})(?![\d.\-/])""")

    /**
     * A day and a four digit year with the month lost to OCR: "8.28.2026" comes back as
     * "L28.2026" or ".28.2026" on hand-lettered flyers. The day and year survive far more
     * reliably than the month, and posters advertise imminent dates, so the next occurrence
     * of that day is a good guess - reported at low confidence so the user checks it.
     */
    private val DAY_AND_YEAR = Regex("""(?<!\d)(\d{1,2})\.(\d{4})(?!\d)""")

    private val RELATIVE = Regex("""\b(today|tonight|this\s+evening|tomorrow|tmrw|tmr|tomorrow\s+night)\b""", RegexOption.IGNORE_CASE)

    private val WEEKDAY = Regex(
        """\b(this|next|nxt|upcoming)?\s*(mondays?|mon|tuesdays?|tues|tue|wednesdays?|weds|wed|thursdays?|thurs|thur|thu|fridays?|fri|saturdays?|sat|sundays?|sun)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val WEEKDAY_INDEX: Map<String, DayOfWeek> = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

    fun extract(lines: List<String>, today: LocalDate): List<DateCandidate> {
        val out = mutableListOf<DateCandidate>()
        lines.forEachIndexed { index, line ->
            out += fromLine(line, index, today)
        }
        return out
    }

    private fun fromLine(line: String, index: Int, today: LocalDate): List<DateCandidate> {
        val found = mutableListOf<DateCandidate>()

        MONTH_DAY.findAll(line).forEach { m ->
            val month = monthOf(m.groupValues[1]) ?: return@forEach
            val day = m.groupValues[2].toIntOrNull() ?: return@forEach
            resolve(month, day, m.groupValues[3], today)?.let { (date, explicitYear) ->
                found += DateCandidate(date, Confidence.HIGH, index, m.range, explicitYear, DateCandidate.Kind.MONTH_NAME)
            }
        }

        if (found.isEmpty()) {
            DAY_MONTH.findAll(line).forEach { m ->
                val day = m.groupValues[1].toIntOrNull() ?: return@forEach
                val month = monthOf(m.groupValues[2]) ?: return@forEach
                resolve(month, day, m.groupValues[3], today)?.let { (date, explicitYear) ->
                    found += DateCandidate(date, Confidence.HIGH, index, m.range, explicitYear, DateCandidate.Kind.MONTH_NAME)
                }
            }
        }

        NUMERIC.findAll(line).forEach { m ->
            val month = m.groupValues[1].toIntOrNull() ?: return@forEach
            val day = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (month !in 1..12 || day !in 1..31) return@forEach
            resolve(month, day, m.groupValues[3], today)?.let { (date, explicitYear) ->
                found += DateCandidate(date, Confidence.MEDIUM, index, m.range, explicitYear, DateCandidate.Kind.NUMERIC)
            }
        }

        NUMERIC_DOTTED.findAll(line).forEach { m ->
            val month = m.groupValues[1].toIntOrNull() ?: return@forEach
            val day = m.groupValues[3].toIntOrNull() ?: return@forEach
            if (month !in 1..12 || day !in 1..31) return@forEach
            resolve(month, day, m.groupValues[4], today)?.let { (date, explicitYear) ->
                found += DateCandidate(date, Confidence.MEDIUM, index, m.range, explicitYear, DateCandidate.Kind.NUMERIC)
            }
        }

        DAY_AND_YEAR.findAll(line).forEach { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@forEach
            val year = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (day !in 1..31) return@forEach
            if (year < today.year || year > today.year + 3) return@forEach
            nextOccurrenceOfDay(day, year, today)?.let { date ->
                found += DateCandidate(date, Confidence.LOW, index, m.range, true, DateCandidate.Kind.NUMERIC)
            }
        }

        RELATIVE.findAll(line).forEach { m ->
            val word = m.groupValues[1].lowercase().replace(Regex("""\s+"""), " ")
            val date = when {
                word.startsWith("tomorrow") || word == "tmrw" || word == "tmr" -> today.plusDays(1)
                else -> today
            }
            found += DateCandidate(date, Confidence.HIGH, index, m.range, false, DateCandidate.Kind.RELATIVE)
        }

        WEEKDAY.findAll(line).forEach { m ->
            val qualifier = m.groupValues[1].lowercase()
            val dow = weekdayOf(m.groupValues[2]) ?: return@forEach
            val base = nextOrSame(today, dow)
            val date = if (qualifier == "next" || qualifier == "nxt") base.plusWeeks(1) else base
            val confidence = when {
                qualifier == "this" || qualifier == "upcoming" -> Confidence.MEDIUM
                qualifier == "next" || qualifier == "nxt" -> Confidence.LOW
                else -> Confidence.MEDIUM
            }
            found += DateCandidate(date, confidence, index, m.range, false, DateCandidate.Kind.WEEKDAY)
        }

        // A weekday that merely decorates an explicit date ("Friday August 29") adds nothing.
        val explicit = found.filter { it.kind != DateCandidate.Kind.WEEKDAY }
        return if (explicit.isNotEmpty()) explicit + found.filter { it.kind == DateCandidate.Kind.WEEKDAY } else found
    }

    /** The first day-of-month [day] in [year] that has not already passed. */
    private fun nextOccurrenceOfDay(day: Int, year: Int, today: LocalDate): LocalDate? {
        var probe = today.withDayOfMonth(1)
        repeat(MONTHS_TO_SEARCH) {
            if (probe.year == year) {
                val candidate = runCatching { LocalDate.of(probe.year, probe.monthValue, day) }.getOrNull()
                if (candidate != null && !candidate.isBefore(today)) return candidate
            }
            probe = probe.plusMonths(1)
        }
        return null
    }

    private val MONTHS_TO_SEARCH = 24

    private fun monthOf(raw: String): Int? = Patterns.MONTH_INDEX[raw.lowercase().take(3)]

    private fun weekdayOf(raw: String): DayOfWeek? = WEEKDAY_INDEX[raw.lowercase().take(3)]

    private fun nextOrSame(today: LocalDate, dow: DayOfWeek): LocalDate {
        val delta = (dow.value - today.dayOfWeek.value + 7) % 7
        return today.plusDays(delta.toLong())
    }

    /**
     * Resolves month/day to a real date. With no explicit year, picks the next occurrence that
     * is not in the past, so "January 3" seen on December 29 lands in the following year.
     */
    private fun resolve(month: Int, day: Int, yearGroup: String, today: LocalDate): Pair<LocalDate, Boolean>? {
        if (month !in 1..12 || day !in 1..31) return null
        if (yearGroup.isNotEmpty()) {
            val n = yearGroup.toIntOrNull() ?: return null
            val year = if (yearGroup.length <= 2) 2000 + n else n
            if (year !in 1990..2100) return null
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { it to true }
        }
        for (year in today.year..(today.year + 4)) {
            val candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            if (!candidate.isBefore(today)) return candidate to false
        }
        return null
    }
}
