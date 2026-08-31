package com.lineup.app.parser

import com.lineup.app.core.Confidence
import com.lineup.app.core.EventDraft
import com.lineup.app.core.FieldConfidence
import com.lineup.app.ocr.OcrLine
import java.time.format.DateTimeFormatter

/**
 * Deterministic, offline poster parser. No model, no network - just the heuristics that
 * cover the shapes event flyers actually use.
 */
class LocalEventParser : EventParser {

    private data class Candidate(val index: Int, val line: OcrLine, val text: String)

    override fun parse(input: ParseInput): EventDraft {
        val candidates = input.ocr.lines
            .map { line -> line to TextUtils.normalize(line.text) }
            .filter { it.second.isNotBlank() && !Patterns.looksLikeAppChrome(it.second) }
            .mapIndexed { i, pair -> Candidate(i, pair.first, pair.second) }

        if (candidates.isEmpty()) return EventDraft.EMPTY

        val texts = candidates.map { it.text }

        val dateCandidates = DateExtractor.extract(texts, input.today)
        val blocked = dateCandidates.groupBy({ it.lineIndex }, { it.range })
        val times = TimeExtractor.extract(texts, blocked)

        val bestDate = dateCandidates
            .sortedWith(compareByDescending<DateCandidate> { it.confidence.ordinal }.thenBy { it.lineIndex })
            .firstOrNull()

        val dateLines = dateCandidates.map { it.lineIndex }.toSet()
        val timeLines = times.timeLineIndices

        val headline = pickTitle(candidates, dateLines, timeLines)
        val title = headline?.let { it.copy(text = composeTitle(candidates, it)) }
        val location = pickLocation(candidates, dateLines, timeLines, title?.indices.orEmpty())

        val description = times.doors?.let { doors ->
            "Doors at " + doors.time.format(TIME_FORMAT)
        }

        return EventDraft(
            title = title?.text,
            date = bestDate?.date,
            startTime = times.start?.time,
            endTime = times.end?.time,
            location = location?.text,
            description = description,
            confidence = FieldConfidence(
                title = title?.confidence ?: Confidence.NONE,
                date = bestDate?.confidence ?: Confidence.NONE,
                startTime = times.start?.confidence ?: Confidence.NONE,
                endTime = times.end?.confidence ?: Confidence.NONE,
                location = location?.confidence ?: Confidence.NONE,
            ),
        )
    }

    // ---------------------------------------------------------------- title

    private data class Scored(
        val index: Int,
        val text: String,
        val confidence: Confidence,
        val indices: Set<Int> = setOf(index),
    )

    private fun pickTitle(
        candidates: List<Candidate>,
        dateLines: Set<Int>,
        timeLines: Set<Int>,
    ): Scored? {
        if (candidates.isEmpty()) return null
        val heights = candidates.mapNotNull { it.line.box?.height }.filter { it > 0 }.sorted()
        val medianHeight = heights.getOrNull(heights.size / 2)?.toDouble()
        val pageBottom = candidates.mapNotNull { it.line.box?.bottom }.maxOrNull()?.toDouble()
        val pageTop = candidates.mapNotNull { it.line.box?.top }.minOrNull()?.toDouble()

        val scores = mutableMapOf<Int, Double>()
        candidates.forEachIndexed { position, candidate ->
            val text = candidate.text
            var score = 0.0

            if (candidate.index in dateLines) score -= 6.0
            if (candidate.index in timeLines) score -= 6.0
            if (Patterns.looksLikeUrl(text)) score -= 9.0
            if (Patterns.looksLikeEmail(text)) score -= 9.0
            if (Patterns.looksLikeHandle(text)) score -= 9.0
            if (Patterns.looksLikePrice(text)) score -= 7.0
            if (Patterns.looksLikeSponsor(text)) score -= 7.0
            if (Patterns.looksLikeCallToAction(text)) score -= 7.0

            val words = TextUtils.wordCount(text)
            score += when {
                words in 1..8 -> 1.5
                words in 9..12 -> -1.0
                else -> -3.0
            }
            if (text.length < 3) score -= 6.0
            if (text.length > 60) score -= 3.0
            if (TextUtils.letterRatio(text) < 0.5) score -= 4.0
            if (TextUtils.isMostlyUpperCase(text)) score += 1.0

            val box = candidate.line.box
            if (box != null && medianHeight != null && medianHeight > 0) {
                // Visual prominence is the strongest signal a poster gives us.
                score += ((box.height / medianHeight) - 1.0).coerceIn(-1.5, 1.7) * 3.0
                if (pageTop != null && pageBottom != null && pageBottom > pageTop) {
                    val relative = (box.centerY - pageTop) / (pageBottom - pageTop)
                    score += when {
                        relative <= 0.55 -> 1.5
                        relative >= 0.8 -> -1.5
                        else -> 0.0
                    }
                }
            } else {
                // No layout information: reading order is the only prominence proxy we have.
                score -= 0.4 * position
            }

            // A question is a hook line, not the name of the event.
            if (text.trimEnd().endsWith("?")) score -= 1.5
            // "Kendeda 230" is where the event is, never what it is called.
            if (Patterns.ROOM_NUMBER.matches(text)) score -= 3.0

            scores[candidate.index] = score
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        val excluded = mutableSetOf<Int>()

        repeat(3) {
            val entry = ranked.firstOrNull { it.key !in excluded && it.value > 0.0 } ?: return null
            val group = mergeWrappedTitle(candidates, entry.key, dateLines, timeLines)
            val text = group.joinToString(" ") { candidates[it].text }
            // "RETURNING YDSA MEMBER?" is a hook, not the name of the event.
            if (text.trimEnd().endsWith("?") && it < 2) {
                excluded += group
                return@repeat
            }
            val candidate = candidates[entry.key]
            val hasLayout = candidate.line.box != null && medianHeight != null
            val confidence = when {
                entry.value >= 4.0 && hasLayout -> Confidence.HIGH
                entry.value >= 2.0 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            return Scored(entry.key, text, confidence, group.toSet())
        }
        return null
    }

    /**
     * Poster titles routinely wrap onto two or three lines. Neighbours are found
     * geometrically rather than by reading order, because sorting a multi-column poster
     * top-to-bottom interleaves unrelated columns.
     *
     * Growth is downward only: the most prominent line of a headline is its first one.
     */
    private fun mergeWrappedTitle(
        candidates: List<Candidate>,
        winnerIndex: Int,
        dateLines: Set<Int>,
        timeLines: Set<Int>,
    ): List<Int> {
        val group = mutableListOf(winnerIndex)
        var anchor = candidates[winnerIndex].line.box ?: return group

        while (group.size < MAX_TITLE_LINES) {
            val next = candidates
                .filter { it.index !in group }
                .filter { continuesHeadline(it, anchor, group, candidates, dateLines, timeLines) }
                .minByOrNull { it.line.box!!.top }
                ?: break
            group += next.index
            anchor = next.line.box!!
        }
        return group.sorted()
    }

    private fun continuesHeadline(
        candidate: Candidate,
        anchor: com.lineup.app.ocr.OcrBox,
        group: List<Int>,
        candidates: List<Candidate>,
        dateLines: Set<Int>,
        timeLines: Set<Int>,
    ): Boolean {
        val box = candidate.line.box ?: return false
        if (candidate.index in dateLines || candidate.index in timeLines) return false
        if (isMetadata(candidate.text)) return false
        if (box.height <= 0 || anchor.height <= 0) return false
        if (box.top <= anchor.top) return false

        val ratio = box.height.toDouble() / anchor.height
        if (ratio < 0.6 || ratio > 1.65) return false

        // Hand-lettered and slanted text overlaps its neighbour, so a small negative gap is fine.
        val gap = box.top - anchor.bottom
        if (gap < -0.5 * anchor.height || gap > 0.8 * anchor.height) return false

        val overlap = minOf(box.right, anchor.right) - maxOf(box.left, anchor.left)
        if (overlap <= 0.25 * minOf(box.width, anchor.width)) return false

        val words = TextUtils.wordCount(candidate.text) +
            group.sumOf { TextUtils.wordCount(candidates[it].text) }
        return words <= MAX_TITLE_WORDS
    }

    /**
     * Room numbers come back from OCR with a letter where a digit should be - "KENDEDA 230"
     * reads as "KENDEDA Z30". Only characters that are never plausible room prefixes are
     * repaired, and only when the whole token then reads as a number, so "Room 2B" and
     * "Building B12" are left alone.
     */
    private fun repairRoomNumber(text: String): String {
        val split = text.lastIndexOf(' ')
        if (split < 0) return text
        val head = text.substring(0, split)
        val tail = text.substring(split + 1)
        if (tail.length < 2) return text

        val repaired = tail.map { DIGIT_LOOKALIKES[it] ?: it }.joinToString("")
        if (repaired == tail) return text
        if (!repaired.all { it.isDigit() }) return text
        // A gained leading zero means the guess was wrong.
        if (repaired.startsWith("0") && !tail.startsWith("0")) return text
        return "$head $repaired"
    }

    private fun isMetadata(text: String) =
        Patterns.looksLikeUrl(text) ||
            Patterns.looksLikeEmail(text) ||
            Patterns.looksLikeHandle(text) ||
            Patterns.looksLikePrice(text) ||
            Patterns.looksLikeSponsor(text) ||
            Patterns.looksLikeCallToAction(text)

    /**
     * Turns the raw headline into something worth reading in an agenda, and prefixes the
     * organiser when the poster identifies one.
     */
    private fun composeTitle(candidates: List<Candidate>, headline: Scored): String {
        val organiser = findOrganiser(candidates, headline.indices)
        val name = TextUtils.formatEventName(
            headline.text,
            preserve = organiser?.split(" ")?.filter { it.isNotBlank() }?.toSet().orEmpty(),
        )
        if (organiser == null) return name
        // "YDSA GT Welcome Back Social" reads better than either half alone, but only if the
        // headline does not already say it.
        if (TextUtils.squash(name).contains(TextUtils.squash(organiser))) return name
        return "$organiser $name"
    }

    /**
     * A social handle is the organiser's name with the spaces taken out, so a poster line
     * that squashes down to a handle seen elsewhere in the image is the organiser written
     * properly. No guessing: the two have to match exactly.
     */
    private fun findOrganiser(candidates: List<Candidate>, titleIndices: Set<Int>): String? {
        val handles = buildSet {
            candidates.forEach { c ->
                if (HANDLE_LINE.matches(c.text)) add(c.text.lowercase())
                Patterns.HANDLE.findAll(c.text).forEach { add(it.value.removePrefix("@").lowercase()) }
            }
        }
        if (handles.isEmpty()) return null

        return candidates.firstOrNull { c ->
            c.index !in titleIndices &&
                c.text.length in 2..30 &&
                TextUtils.wordCount(c.text) <= 4 &&
                c.text != c.text.lowercase() &&
                TextUtils.squash(c.text) in handles
        }?.text?.trim()
    }

    // ------------------------------------------------------------- location

    private val explicitPrefix = Regex(
        """^(?:location|where|venue|place|address|loc)\s*[:\-]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val atPrefix = Regex("""(?:^|\s)(?:at|@)\s+(\S.*)$""", RegexOption.IGNORE_CASE)

    private fun pickLocation(
        candidates: List<Candidate>,
        dateLines: Set<Int>,
        timeLines: Set<Int>,
        titleIndices: Set<Int>,
    ): Scored? {
        fun disqualified(text: String) = isMetadata(text) || Patterns.isBareAppName(text)

        // 1. An explicit label is as good as it gets.
        candidates.forEach { c ->
            explicitPrefix.find(c.text)?.let { m ->
                val value = m.groupValues[1].trim()
                if (value.isNotBlank() && !disqualified(value)) {
                    return Scored(c.index, value, Confidence.HIGH)
                }
            }
        }

        // 2. "at <place>" / "@ <place>", as long as the tail is not a time or a handle.
        candidates.forEach { c ->
            if (c.index in titleIndices || disqualified(c.text)) return@forEach
            atPrefix.find(c.text)?.let { m ->
                val value = m.groupValues[1].trim().trimEnd('.', ',')
                val plausible = value.length >= 3 &&
                    value.any { it.isLetter() } &&
                    !value.first().isDigit() &&
                    c.index !in dateLines &&
                    c.index !in timeLines &&
                    !disqualified(value)
                if (plausible) return Scored(c.index, value, Confidence.MEDIUM)
            }
        }

        val remaining = candidates.filter {
            it.index !in titleIndices &&
                it.index !in dateLines &&
                it.index !in timeLines &&
                !disqualified(it.text) &&
                it.text.length >= 3 &&
                TextUtils.wordCount(it.text) <= 8
        }

        // 3. A recognisable venue word or a "<Building> <room>" shape.
        remaining.firstOrNull { Patterns.hasVenueWord(it.text) || Patterns.ROOM_NUMBER.matches(it.text) }
            ?.let { return Scored(it.index, repairRoomNumber(it.text), Confidence.MEDIUM) }

        // 4. Last resort: a line that reads like a place, sitting just under the date and
        //    time. Both halves matter - without the proximity rule this reaches down into a
        //    screenshot's comments, and without the validation it accepts anything short.
        val anchor = (dateLines + timeLines).maxOrNull() ?: return null
        return remaining
            .lastOrNull { it.index in (anchor + 1)..(anchor + FALLBACK_REACH) && Patterns.looksLikePlace(it.text) }
            ?.let { Scored(it.index, repairRoomNumber(it.text), Confidence.LOW) }
    }

    private companion object {
        /** A bare social handle on its own line, as screenshots of posts always carry. */
        val HANDLE_LINE = Regex("""^[a-z0-9._]{3,30}$""")

        /** Letters that are never sensible room prefixes but are read for digits constantly. */
        val DIGIT_LOOKALIKES = mapOf('Z' to '2', 'z' to '2', 'I' to '1', 'l' to '1', 'O' to '0', 'o' to '0')

        /** How far below the date/time a venue may sit before it is just other text. */
        const val FALLBACK_REACH = 3

        const val MAX_TITLE_LINES = 3
        const val MAX_TITLE_WORDS = 12

        // Pinned locale so the generated description is stable regardless of device settings.
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
    }
}
