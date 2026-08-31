package com.lineup.app.parser

/**
 * Shared patterns and line classifiers.
 *
 * Everything here is a fact about *form* - how dates, times, URLs, handles and prices are
 * written - or about English's closed classes. There are deliberately no vocabularies of
 * venues, calls to action, sponsors or app names: those lists only ever memorise the
 * posters already seen, and every one of them had to be hand-patched the first time a new
 * poster arrived. Where a judgement needs evidence, it comes from layout and corroboration
 * instead.
 */
internal object Patterns {

    const val MONTH =
        "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|" +
            "aug(?:ust)?|sep(?:t|tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"

    val MONTH_INDEX: Map<String, Int> = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    val URL = Regex("""(?:https?://|www\.)\S+|\b[\w-]{2,}\.(?:com|org|net|edu|gov|io|co|us|info|app|ly|me|tv)\b(?:/\S*)?""", RegexOption.IGNORE_CASE)
    val EMAIL = Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""")
    val HANDLE = Regex("""(?<![\w])@[A-Za-z][A-Za-z0-9._]{1,}""")
    val PRICE = Regex("""[$£€]\s?\d|\b\d+\s?(?:dollars|bucks)\b""", RegexOption.IGNORE_CASE)

    fun looksLikeUrl(s: String) = URL.containsMatchIn(s)
    fun looksLikeEmail(s: String) = EMAIL.containsMatchIn(s)
    fun looksLikeHandle(s: String) = HANDLE.containsMatchIn(s)
    fun looksLikePrice(s: String) = PRICE.containsMatchIn(s)

    /**
     * "CULC 144", "Klaus 1447", "Room 2B" - a name followed by a room/street number.
     * A stray leading letter is tolerated because OCR reads "230" as "Z30" on stylised text.
     */
    val ROOM_NUMBER = Regex("""^[A-Za-z][A-Za-z.&'\-/ ]{1,24}\s+[A-Za-z]?\d{1,4}[A-Za-z]?$""")

    /**
     * The acronym a phrase would abbreviate to: the initials of its substantial words.
     * "Young Democratic Socialists of America" -> "YDSA". Short words are skipped, since
     * nobody puts the "of" in.
     *
     * This makes no judgement about whether the phrase names an organisation - that is
     * established by finding the acronym itself elsewhere on the poster.
     */
    fun acronymOf(text: String): String? {
        val words = text.trim().split(Regex("""\s+""")).filter { it.any(Char::isLetter) }
        if (words.size < 3) return null
        val initials = words
            .filter { it.trim('.', ',', ':').length > 2 }
            .mapNotNull { word -> word.firstOrNull { it.isLetter() }?.uppercaseChar() }
        if (initials.size !in 3..6) return null
        return initials.joinToString("")
    }

    /** A line that is nothing but an acronym: "YDSA", "ASME", "GT". */
    private val ACRONYM_LINE = Regex("""^[A-Z][A-Z0-9]{1,5}$""")

    fun isAcronymLine(text: String): Boolean = ACRONYM_LINE.matches(text.trim().trim('.', ',', '!'))

    /**
     * Levenshtein distance, for comparing an acronym read off artwork against one derived
     * from a spelled-out name. Stylised lettering costs a character or two - the "Y" of
     * "YDSA" came back as a "V" - so the two forms have to agree loosely, not exactly.
     */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }

    /**
     * Whether an unlabelled line could be a venue at all. This is a filter on *shape*, not
     * a claim that the line is a place - the evidence that it is comes from where it sits
     * relative to the date and time, which the caller weighs.
     *
     * Rejected: sentence punctuation (a venue is not exclaimed, questioned, or left hanging
     * on a comma), single words, long phrases, and anything mostly non-alphabetic.
     */
    fun couldBePlace(s: String): Boolean {
        val text = s.trim()
        if (text.length < 3) return false
        if (text.endsWith("!") || text.endsWith("?") || text.endsWith(",")) return false
        if (TextUtils.letterRatio(text) < 0.4) return false

        val words = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        // One word is a label, not an address; six is a sentence.
        return words.size in 2..5 || ROOM_NUMBER.matches(text)
    }
}

internal object TextUtils {

    /** Collapse the punctuation OCR loves to mangle, without changing character offsets' meaning. */
    fun normalize(s: String): String = s
        .replace(' ', ' ')
        .replace('‘', '\'')
        .replace('’', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('：', ':')
        .replace(Regex("""[ \t]+"""), " ")
        .trim()

    fun letterRatio(s: String): Double {
        val relevant = s.count { !it.isWhitespace() }
        if (relevant == 0) return 0.0
        return s.count { it.isLetter() }.toDouble() / relevant
    }

    fun wordCount(s: String): Int = s.trim().split(Regex("""\s+""")).count { it.isNotBlank() }

    fun isMostlyUpperCase(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 3) return false
        return letters.count { it.isUpperCase() }.toDouble() / letters.length >= 0.8
    }

    fun overlaps(a: IntRange, b: IntRange): Boolean = a.first <= b.last && b.first <= a.last

    /** Letters and digits only, lowercased: "YDSA GT" and "ydsagt" become the same key. */
    fun squash(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * English's closed class of short words. Needed to tell a word from an initialism when
     * a poster capitalises everything: "OFF" is a word, "GT" is not.
     */
    private val SHORT_WORDS = setOf(
        "a", "an", "the", "and", "or", "of", "in", "on", "at", "to", "for", "off", "out",
        "up", "our", "all", "new", "one", "two", "day", "eve", "fun", "big", "end", "not",
        "you", "get", "see", "now", "let", "is", "it", "we", "be", "by", "vs", "no", "so",
    )

    private fun isAcronym(word: String): Boolean {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty() || letters.length > 3) return false
        if (letters.all { it.isUpperCase() }.not()) return false
        return letters.lowercase() !in SHORT_WORDS
    }

    /**
     * Posters shout; calendars do not. Title-cases a headline that is set in capitals,
     * keeping short acronyms ("GT", "CS") intact, and drops the trailing punctuation that
     * reads as excitement on a poster and as noise in an agenda.
     *
     * Mixed-case text is left exactly as written - somebody chose that capitalisation.
     */
    fun formatEventName(raw: String, preserve: Set<String> = emptySet()): String {
        val trimmed = raw.trim().trimEnd('!', '.', ',', ':', ';', '-', '–', '—', '*').trim()
        if (trimmed.isEmpty()) return raw.trim()
        if (!isMostlyUpperCase(trimmed)) return trimmed
        return trimmed.split(" ").joinToString(" ") { word ->
            // An organisation writes its own name; "YDSA" must not become "Ydsa".
            if (word in preserve || isAcronym(word)) {
                word
            } else {
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
    }
}
