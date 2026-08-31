package com.lineup.app.parser

import com.lineup.app.core.Confidence
import com.lineup.app.ocr.OcrBlock
import com.lineup.app.ocr.OcrBox
import com.lineup.app.ocr.OcrLine
import com.lineup.app.ocr.OcrText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Regression fixtures captured from real ML Kit output on a physical device
 * (`adb logcat -s LineupOcr`), layout included. These are the cases that actually failed.
 */
class RealPosterTest {

    private val parser = LocalEventParser()
    private val now: ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(2026, 8, 26, 22, 41), ZoneId.of("America/New_York"))

    private fun fixture(name: String, width: Int, height: Int): OcrText {
        val rows = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "missing fixture $name"
        }.bufferedReader().readLines().filter { it.isNotBlank() }

        val lines = rows.map { row ->
            val (boxPart, text) = row.split('\t', limit = 2)
            val (l, t, r, b) = boxPart.split(',').map { it.trim().toInt() }
            OcrLine(text, OcrBox(l, t, r, b))
        }
        return OcrText(
            raw = lines.joinToString("\n") { it.text },
            blocks = lines.map { OcrBlock(it.text, it.box, listOf(it)) },
            imageWidth = width,
            imageHeight = height,
        )
    }

    private operator fun <T> List<T>.component4(): T = this[3]

    /**
     * An Instagram screenshot of a hand-lettered flyer. The visually dominant text is a hook
     * ("RETURNING YDSA MEMBER?"), the real title is lower down, and the whole Instagram
     * chrome is in the frame. ML Kit mangled the rotated handwriting:
     * "KENDEDA 230" -> "KENDEDA Z30", "8.28.2026" -> "L28.2026", "6-7:30 PM" -> "6-130 M1".
     */
    @Test
    fun `instagram screenshot of a hand lettered flyer`() {
        val draft = parser.parse(ParseInput(fixture("poster_instagram_ydsa.tsv", 864, 1939), now))

        // The account is "ydsagt" and the poster art says "YDSA GT": same thing, so the
        // organiser is named, and the OCR typo in "BACk" dissolves in the title casing.
        assertEquals("YDSA GT Welcome Back Social", draft.title)
        assertEquals("KENDEDA 230", draft.location)

        // "8.28.2026" came back as "L28.2026": the month is gone but the day and year
        // survived, so the next 28th is recovered - flagged low so the user checks it.
        assertEquals(LocalDate.of(2026, 8, 28), draft.date)
        assertEquals(Confidence.LOW, draft.confidence.date)

        // "6-7:30 PM" came back as "6-130 M1". The 7 is misread as a 1 at every scale and
        // angle tried, so no time is offered rather than a wrong one.
        assertNull(draft.startTime)
        assertNull(draft.endTime)
    }

    /**
     * A screenshot of an email newsletter containing a flyer. The chapter badge reads
     * "ASME / AT GEORGIA TECH / STUDENT SECTION", which the "at <place>" rule used to
     * capture as the venue, beating the real one under the date.
     */
    @Test
    fun `an email newsletter flyer with a decoy at-phrase`() {
        val draft = parser.parse(
            ParseInput(fixture("poster_email_career_fair.tsv", 1080, 2424), now),
        )

        assertEquals(LocalDate.of(2026, 9, 14), draft.date)
        assertEquals(LocalTime.of(9, 0), draft.startTime)
        assertEquals(LocalTime.of(15, 0), draft.endTime)

        // The venue under the date, not the "AT GEORGIA TECH" in the society's badge.
        assertEquals("GEORGIA TECH EXHIBITION HALL", draft.location)
        assertEquals("Fall Career Fair", draft.title)
    }
}
