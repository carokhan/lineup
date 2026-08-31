package com.lineup.app.core

/**
 * A record of an event this app wrote, kept so the user can go back and check that one
 * actually landed. Deliberately not a database: a short list in preferences is enough.
 */
data class SavedEvent(
    val eventId: Long,
    val title: String,
    val startMillis: Long?,
    val calendarName: String,
    val savedAtMillis: Long,
)

/**
 * A tiny delimited encoding rather than JSON, so the round trip is plain Kotlin and can be
 * unit tested without Android's org.json stubs. The delimiters are the ASCII unit and
 * record separators, so stripping them from user text cannot mangle anything real.
 */
object SavedEventCodec {

    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    fun encode(events: List<SavedEvent>): String = events.joinToString(RECORD.toString()) { event ->
        listOf(
            event.eventId.toString(),
            event.startMillis?.toString().orEmpty(),
            event.savedAtMillis.toString(),
            event.calendarName.sanitised(),
            event.title.sanitised(),
        ).joinToString(FIELD.toString())
    }

    fun decode(raw: String?): List<SavedEvent> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RECORD).mapNotNull { record ->
            val parts = record.split(FIELD)
            if (parts.size != 5) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val savedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
            SavedEvent(
                eventId = id,
                startMillis = parts[1].toLongOrNull(),
                savedAtMillis = savedAt,
                calendarName = parts[3],
                title = parts[4],
            )
        }
    }

    private fun String.sanitised(): String = filterNot { it == FIELD || it == RECORD }
}
