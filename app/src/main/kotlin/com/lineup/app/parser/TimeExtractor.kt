package com.lineup.app.parser

import com.lineup.app.core.Confidence
import java.time.LocalTime

internal enum class TimeLabel { DOORS, SHOW, GENERIC }

internal data class TimeCandidate(
    val time: LocalTime,
    val label: TimeLabel,
    val confidence: Confidence,
    val lineIndex: Int,
    val range: IntRange,
)

internal data class TimeExtraction(
    val start: TimeCandidate? = null,
    val end: TimeCandidate? = null,
    val doors: TimeCandidate? = null,
    val timeLineIndices: Set<Int> = emptySet(),
)

/**
 * Poster time parsing. Handles 12h/24h, ranges, and the "doors 7 / show 8" idiom.
 *
 * A bare number ("7") only counts as a time when a keyword or a range partner vouches for it,
 * which keeps room numbers like "CULC 144" or "Klaus 1447" out of the results.
 */
internal object TimeExtractor {

    private val TOKEN = Regex(
        """(?<![\d:.])(\d{1,4})(?::(\d{2}))?\s*(a\.?\s?m\.?|p\.?\s?m\.?)?(?![\d])""",
        RegexOption.IGNORE_CASE,
    )

    private val RANGE_SEP = Regex("""^\s*(?:[-–—~]|to|until|til|till|thru|through)\s*$""", RegexOption.IGNORE_CASE)

    private val DOORS_WORDS = listOf("doors", "door open", "doors open", "door")
    private val SHOW_WORDS = listOf(
        "showtime", "show time", "show", "starts", "start", "begins", "begin", "kickoff",
        "kick off", "tipoff", "tip off", "performance", "curtain", "music", "event begins",
    )
    private val GENERIC_WORDS = listOf("at", "@", "from", "time")

    private data class RawToken(
        val hour: Int,
        val minute: Int,
        val meridiem: Char?, // 'a', 'p', or null
        val hadColon: Boolean,
        val range: IntRange,
        val impliedMinutes: Boolean = false,
    ) {
        /** Strong enough to stand on its own, without a keyword or a range partner. */
        val strong: Boolean get() = meridiem != null || (hadColon && !impliedMinutes)
        val is24h: Boolean get() = meridiem == null && (hour == 0 || hour > 12)
    }

    fun extract(lines: List<String>, blockedRanges: Map<Int, List<IntRange>>): TimeExtraction {
        val all = mutableListOf<TimeCandidate>()
        var rangeStart: TimeCandidate? = null
        var rangeEnd: TimeCandidate? = null
        val timeLines = mutableSetOf<Int>()

        lines.forEachIndexed { index, line ->
            val blocked = blockedRanges[index].orEmpty()
            val tokens = TOKEN.findAll(line)
                .mapNotNull { toRawToken(it) }
                .filterNot { t -> blocked.any { TextUtils.overlaps(it, t.range) } }
                .toList()
            if (tokens.isEmpty()) return@forEachIndexed

            val labels = labelsOf(line, tokens)
            val pairs = rangePairs(line, tokens)

            val accepted = mutableMapOf<Int, TimeCandidate>() // token index -> candidate
            tokens.forEachIndexed { ti, token ->
                val partner = pairs[ti]?.let { tokens[it] }
                val label = labels[ti] ?: TimeLabel.GENERIC
                val vouched = token.strong || labels[ti] != null || (partner != null && partner.strong)
                if (!vouched) return@forEachIndexed
                val resolved = resolve(token, partner, tokens) ?: return@forEachIndexed
                accepted[ti] = TimeCandidate(resolved.first, label, resolved.second, index, token.range)
            }
            if (accepted.isEmpty()) return@forEachIndexed
            timeLines += index

            // A range on this line wins for start/end, unless one was already found earlier.
            pairs.entries.sortedBy { it.key }.forEach { (a, b) ->
                val ca = accepted[a]
                val cb = accepted[b]
                if (ca != null && cb != null && rangeStart == null) {
                    val (fixedA, fixedB) = fixRangeOrder(ca, cb, tokens[a], tokens[b])
                    rangeStart = fixedA
                    rangeEnd = fixedB
                }
            }
            all += accepted.entries.sortedBy { it.key }.map { it.value }
        }

        val doors = all.firstOrNull { it.label == TimeLabel.DOORS }
        val show = all.firstOrNull { it.label == TimeLabel.SHOW }

        val start = show
            ?: rangeStart
            ?: doors
            ?: all.firstOrNull()
        var end = rangeEnd
        if (end != null && start != null && end.range == start.range && end.lineIndex == start.lineIndex) end = null

        return TimeExtraction(
            start = start,
            end = end,
            doors = doors?.takeIf { start != null && it.time != start.time },
            timeLineIndices = timeLines,
        )
    }

    private fun toRawToken(m: MatchResult): RawToken? {
        val digits = m.groupValues[1]
        val minuteRaw = m.groupValues[2]
        val mer = m.groupValues[3].lowercase().firstOrNull { it == 'a' || it == 'p' }

        var hour = digits.toIntOrNull() ?: return null
        var minute = if (minuteRaw.isEmpty()) 0 else minuteRaw.toIntOrNull() ?: return null
        var impliedMinutes = false

        if (minuteRaw.isEmpty() && digits.length >= 3) {
            // OCR routinely drops the colon: "6-730 PM". Only ever accepted when something
            // else on the line vouches for it, which keeps "KLAUS 1447" a room number.
            hour = digits.toInt() / 100
            minute = digits.toInt() % 100
            impliedMinutes = true
        } else if (digits.length > 2) {
            return null
        }

        if (minute !in 0..59) return null
        if (hour !in 0..23) return null
        if (mer != null && hour !in 1..12) return null
        return RawToken(hour, minute, mer, minuteRaw.isNotEmpty() || impliedMinutes, m.range, impliedMinutes)
    }

    /** Maps token index -> label, based on the nearest keyword on the line. */
    private fun labelsOf(line: String, tokens: List<RawToken>): Map<Int, TimeLabel> {
        val lower = line.lowercase()
        val result = mutableMapOf<Int, TimeLabel>()
        val claimed = mutableSetOf<Int>()

        fun apply(words: List<String>, label: TimeLabel) {
            words.forEach { word ->
                var from = 0
                while (true) {
                    val at = lower.indexOf(word, from)
                    if (at < 0) break
                    from = at + word.length
                    val before = lower.getOrNull(at - 1)
                    val after = lower.getOrNull(at + word.length)
                    val isWord = (before == null || !before.isLetter()) && (after == null || !after.isLetter())
                    if (!isWord) continue
                    val ti = nearestToken(tokens, at, claimed) ?: continue
                    claimed += ti
                    result[ti] = label
                }
            }
        }

        apply(DOORS_WORDS, TimeLabel.DOORS)
        apply(SHOW_WORDS, TimeLabel.SHOW)
        apply(GENERIC_WORDS, TimeLabel.GENERIC)
        return result
    }

    private fun nearestToken(tokens: List<RawToken>, keywordAt: Int, claimed: Set<Int>): Int? {
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        tokens.forEachIndexed { i, t ->
            if (i in claimed) return@forEachIndexed
            val distance = if (t.range.first >= keywordAt) {
                (t.range.first - keywordAt) // prefer the token after the keyword
            } else {
                (keywordAt - t.range.last) + 6
            }
            if (distance in 0 until bestDistance && distance <= 24) {
                best = i
                bestDistance = distance
            }
        }
        return best
    }

    /** Token index -> index of the token it forms a range with. */
    private fun rangePairs(line: String, tokens: List<RawToken>): Map<Int, Int> {
        val pairs = mutableMapOf<Int, Int>()
        for (i in 0 until tokens.size - 1) {
            val between = line.substring(tokens[i].range.last + 1, tokens[i + 1].range.first)
            if (RANGE_SEP.matches(between)) pairs[i] = i + 1
        }
        return pairs
    }

    private fun resolve(token: RawToken, partner: RawToken?, siblings: List<RawToken>): Pair<LocalTime, Confidence>? {
        token.meridiem?.let { mer ->
            val hour = when {
                mer == 'a' && token.hour == 12 -> 0
                mer == 'p' && token.hour != 12 -> token.hour + 12
                else -> token.hour
            }
            return LocalTime.of(hour, token.minute) to Confidence.HIGH
        }
        if (token.is24h) return LocalTime.of(token.hour, token.minute) to Confidence.HIGH

        // Borrow the meridiem from a range partner, then from any other explicit time on the line.
        val borrowed = partner?.meridiem ?: siblings.firstOrNull { it.meridiem != null }?.meridiem
        if (borrowed != null) {
            val hour = when {
                borrowed == 'a' && token.hour == 12 -> 0
                borrowed == 'p' && token.hour != 12 -> token.hour + 12
                else -> token.hour
            }
            return LocalTime.of(hour, token.minute) to Confidence.HIGH
        }

        // Nothing to go on: posters are overwhelmingly evening events. Say so, but say it quietly.
        if (token.hour !in 1..12) return null
        val hour = if (token.hour == 12) 12 else token.hour + 12
        val confidence = if (token.hour in 5..11) Confidence.MEDIUM else Confidence.LOW
        return LocalTime.of(hour, token.minute) to confidence
    }

    /** "11-1 PM" means 11 AM to 1 PM, not 11 PM to 1 PM. */
    private fun fixRangeOrder(
        start: TimeCandidate,
        end: TimeCandidate,
        startToken: RawToken,
        endToken: RawToken,
    ): Pair<TimeCandidate, TimeCandidate> {
        if (start.time < end.time) return start to end
        if (startToken.meridiem != null || startToken.is24h) return start to end
        val flipped = start.time.minusHours(12)
        return if (flipped < end.time && flipped.hour in 1..23) {
            // We had to guess that the start was morning to make the range run forwards.
            // That is a guess worth showing the user, not a fact.
            start.copy(time = flipped, confidence = Confidence.LOW) to
                end.copy(confidence = minOf(end.confidence, Confidence.MEDIUM))
        } else {
            start to end
        }
    }
}
