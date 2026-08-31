package com.lineup.app.ai

import com.lineup.app.core.Confidence
import com.lineup.app.parser.LocalEventParser
import com.lineup.app.parser.ParseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PosterPromptTest {

    private val parser = LocalEventParser()
    private val now: ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(2026, 8, 26, 22, 41), ZoneId.of("America/New_York"))

    @Test
    fun `the prompt asks for a short poster-only transcription`() {
        val prompt = PosterPrompt.TRANSCRIBE.lowercase()
        assertTrue(prompt.contains("transcribe"))
        assertTrue(prompt.contains("exactly"))
        // Skipping the host app's interface roughly halves the tokens generated.
        assertTrue(prompt.contains("skip app interface"))
        // Prompt prefill is on the critical path, so keep it short.
        assertTrue(PosterPrompt.TRANSCRIBE.length < 260)
    }

    /**
     * The exact reply Gemini Nano gave for the hand-lettered flyer that ML Kit could not
     * read, captured from a Pixel 9 via `adb logcat -s LineupNano`.
     */
    private val nanoReply = """
        Kendeda 230
        8.28.2026
        6-7:30 PM

        RETURNING
        YDSA
        MEMBER?

        DOM US FOR A
        WELCOME BACK
        SOCIAL!

        FOOD, DRINKS,
        AND GAMES!!

        17
        2
        5

        Liked by someone and 5 others

        ydsagt Welcome back all old members and new members!
        We will be having a welcome back social in Kended... more
        2 days ago

        ydsagt and 2 others
        🎶 Nina Simone · New World Coming
    """.trimIndent()

    @Test
    fun `the transcription recovers the date and time ocr could not read`() {
        val transcript = PosterPrompt.cleanTranscription(nanoReply)!!
        val draft = parser.parse(ParseInput(transcript, now))

        assertEquals(LocalDate.of(2026, 8, 28), draft.date)
        assertEquals(LocalTime.of(18, 0), draft.startTime)
        assertEquals(LocalTime.of(19, 30), draft.endTime)
        assertEquals("Kendeda 230", draft.location)
        assertEquals(Confidence.HIGH, draft.confidence.startTime)
        // Without layout the headline is not recoverable from reading order alone, but the
        // room number must never be mistaken for the event's name.
        assertNotEquals("Kendeda 230", draft.title)
    }

    @Test
    fun `model scaffolding is stripped`() {
        val transcript = PosterPrompt.cleanTranscription(
            """
            Sure! Here is the text in the image:
            ```
            * SPRING GALA
            - September 4
            8 PM
            ```
            """.trimIndent()
        )!!
        assertEquals(listOf("SPRING GALA", "September 4", "8 PM"), transcript.lines.map { it.text })
    }

    @Test
    fun `a refusal never reaches the parser as poster text`() {
        assertNull(PosterPrompt.cleanTranscription("I'm sorry, but I can't help with that."))
        assertNull(PosterPrompt.cleanTranscription(""))
        assertNull(PosterPrompt.cleanTranscription(null))
        assertNull(PosterPrompt.cleanTranscription("   \n  \n "))
    }

    @Test
    fun `a prose description is rejected rather than parsed`() {
        val description = "This image shows a red poster with a large rose illustration in the " +
            "centre and several lines of hand written text arranged around it in yellow and black."
        assertNull(PosterPrompt.cleanTranscription(description))
    }
}
