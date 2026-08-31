package com.lineup.app.parser

/** Shared regexes and line classifiers used by the extractors. */
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

    private val SPONSOR_WORDS = listOf(
        "presented by", "presents", "sponsored by", "brought to you by", "in partnership with",
        "hosted by", "powered by", "a production of",
    )
    private val CTA_WORDS = listOf(
        "register now", "register", "rsvp", "sign up", "signup", "learn more", "buy tickets",
        "tickets", "ticket", "get tickets", "scan", "qr code", "swipe up", "link in bio",
        "follow us", "more info", "details at", "click", "join us", "free entry",
        "limited seats", "seats limited", "all ages", "21+", "18+",
    )
    private val VENUE_WORDS = listOf(
        "hall", "room", "rm ", "center", "centre", "theater", "theatre", "club", "bar",
        "lounge", "library", "auditorium", "plaza", "green", "park", "building", "campus",
        "stage", "arena", "gym", "cafe", "café", "house", "rooftop", "lawn", "field",
        "court", "studio", "deck", "commons", "ballroom", "pavilion", "amphitheater",
        "st", "ave", "avenue", "street", "blvd", "road", "suite", "floor", "atrium",
        "quad", "stadium", "museum", "gallery", "chapel", "church", "school", "college",
    )

    /**
     * Apps and platforms whose names turn up in screenshots as interface furniture. A line
     * that is *only* one of these is never a venue; "Instagram Workshop" still is.
     */
    private val BARE_APP_NAMES = setOf(
        "instagram", "facebook", "twitter", "x", "tiktok", "snapchat", "whatsapp", "discord",
        "reddit", "youtube", "linkedin", "pinterest", "tumblr", "threads", "bereal", "messenger",
        "telegram", "signal", "gmail", "photos", "google photos", "messages", "chrome", "safari",
        "posts", "reels", "stories", "feed", "home", "search", "profile", "settings", "share",
        "canvas", "outlook", "slack", "notion", "drive", "files",
    )

    /** Words that start a continuation, never a venue name. */
    private val LEAD_INS = setOf(
        "and", "or", "but", "with", "plus", "featuring", "feat", "ft", "also", "then",
        "including", "presented", "sponsored", "hosted", "powered",
    )

    /**
     * Words that name an organising body. Deliberately narrow: "school", "department" and
     * "students" appear all over flyers in contexts that are not the host's name, e.g. the
     * "George W. Woodruff School" credit on a careers flyer.
     */
    private val ORGANISATION_WORDS = setOf(
        "socialists", "society", "association", "club", "union", "chapter", "council",
        "fraternity", "sorority", "federation", "alliance", "coalition", "guild", "league",
    )

    /**
     * The acronym a formal organisation name stands for, when the line clearly is one.
     * "Young Democratic Socialists of America" -> "YDSA", which is how anyone would
     * actually refer to it in a calendar.
     */
    fun organisationAcronym(text: String): String? {
        val words = text.trim().split(Regex("""\s+""")).filter { it.any(Char::isLetter) }
        if (words.size < 3) return null
        if (words.none { it.lowercase().trim('.', ',') in ORGANISATION_WORDS }) return null

        // Short words like "of" and "the" are not part of the acronym.
        val initials = words
            .filter { it.trim('.', ',').length > 2 }
            .mapNotNull { word -> word.firstOrNull { it.isLetter() }?.uppercaseChar() }
        if (initials.size !in 3..6) return null
        return initials.joinToString("")
    }

    fun isBareAppName(s: String): Boolean = s.trim().trim('.', '!', '?').lowercase() in BARE_APP_NAMES

    /**
     * Positive evidence that an unlabelled line is a place, used for the weakest inference -
     * the short line sitting under the date and time. Without this the fallback will happily
     * pick up "Instagram", "AND GAMES!!" or a song credit.
     */
    fun looksLikePlace(s: String): Boolean {
        val text = s.trim()
        if (text.length < 3) return false
        if (isBareAppName(text)) return false
        // Exclamations and questions are poster voice, not addresses.
        if (text.endsWith("!") || text.endsWith("?")) return false
        if (TextUtils.letterRatio(text) < 0.4) return false

        // A trailing comma means the sentence continues; venues do not.
        if (text.endsWith(",")) return false

        val words = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return false
        if (words.first().lowercase().trim(',') in LEAD_INS) return false

        // Real evidence only. Merely being two words apart is how "ALL WELCOME" and
        // "FOOD, DRINKS" used to pass for venues.
        val namedPlace = hasVenueWord(text)
        val roomNumber = ROOM_NUMBER.matches(text)
        // "The Eastern", "The Tabernacle" - the article marks a proper noun.
        val properNoun = words.size >= 2 && words.first().lowercase() == "the"
        return namedPlace || roomNumber || properNoun
    }

    fun looksLikeUrl(s: String) = URL.containsMatchIn(s)
    fun looksLikeEmail(s: String) = EMAIL.containsMatchIn(s)
    fun looksLikeHandle(s: String) = HANDLE.containsMatchIn(s)
    fun looksLikePrice(s: String) = PRICE.containsMatchIn(s)

    fun looksLikeSponsor(s: String): Boolean {
        val l = s.lowercase()
        return SPONSOR_WORDS.any { l.contains(it) }
    }

    fun looksLikeCallToAction(s: String): Boolean {
        val l = s.lowercase().trim()
        return CTA_WORDS.any { w -> l == w || l.startsWith("$w ") || l.contains(" $w") }
    }

    private val VENUE_REGEX = Regex(
        "\\b(?:" + VENUE_WORDS.joinToString("|") { Regex.escape(it.trim()) } + ")\\b",
        RegexOption.IGNORE_CASE,
    )

    fun hasVenueWord(s: String): Boolean = VENUE_REGEX.containsMatchIn(s)

    /**
     * "CULC 144", "Klaus 1447", "Room 2B" - a name followed by a room/street number.
     * A stray leading letter is tolerated because OCR reads "230" as "Z30" on stylised text.
     */
    val ROOM_NUMBER = Regex("""^[A-Za-z][A-Za-z.&'\-/ ]{1,24}\s+[A-Za-z]?\d{1,4}[A-Za-z]?$""")

    /**
     * Screenshots of posters carry the host app's UI with them. None of it is ever part of
     * the event, so it is dropped before any heuristic runs.
     */
    private val APP_CHROME = listOf(
        Regex("""^posts?$""", RegexOption.IGNORE_CASE),
        Regex("""^reels?$""", RegexOption.IGNORE_CASE),
        Regex("""^stories$""", RegexOption.IGNORE_CASE),
        Regex("""^explore$""", RegexOption.IGNORE_CASE),
        Regex("""^(for you|suggested for you|sponsored|promoted|ad)$""", RegexOption.IGNORE_CASE),
        Regex("""^(follow|following|followers|message|send|share|save|repost|remix)$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+$"""),
        Regex("""^\d+\s+(likes?|comments?|views?|shares?|replies)$""", RegexOption.IGNORE_CASE),
        Regex("""liked by .+ and (\d+ )?others?""", RegexOption.IGNORE_CASE),
        Regex("""^view (all )?\d+ comments?$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+\s+(second|minute|hour|day|week|month|year)s?\s+ago$""", RegexOption.IGNORE_CASE),
        Regex("""^\S+ and \d+ others?$""", RegexOption.IGNORE_CASE),
        Regex("""^(add|write) a comment""", RegexOption.IGNORE_CASE),
        Regex("""^(original audio|see translation|show translation)$""", RegexOption.IGNORE_CASE),
    )

    fun looksLikeAppChrome(s: String): Boolean {
        val t = s.trim()
        return APP_CHROME.any { it.containsMatchIn(t) }
    }
}

internal object TextUtils {

    /** Collapse the punctuation OCR loves to mangle, without changing character offsets' meaning. */
    fun normalize(s: String): String = s
        .replace(' ', ' ')
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

    /** Short words that are words, not initialisms, however a poster capitalises them. */
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
