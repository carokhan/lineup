package com.lineup.app.parser

import com.lineup.app.core.Confidence
import com.lineup.app.core.EventDraft
import com.lineup.app.ocr.OcrText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class LocalEventParserTest {

    private val parser = LocalEventParser()
    private val zone: ZoneId = ZoneId.of("America/New_York")

    /** Tuesday, 2026-08-25, 10:00 local. */
    private val defaultNow: ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(2026, 8, 25, 10, 0), zone)

    private fun parse(text: String, now: ZonedDateTime = defaultNow): EventDraft =
        parser.parse(ParseInput(OcrText.of(text), now))

    // ------------------------------------------------------------ end-to-end posters

    @Test
    fun `parses a classic poster`() {
        val draft = parse(
            """
            TECH SQUARE SOCIAL
            FRIDAY AUGUST 28
            7 PM
            TECH GREEN
            """
        )
        assertEquals("Tech Square Social", draft.title)
        assertEquals(LocalDate.of(2026, 8, 28), draft.date)
        assertEquals(LocalTime.of(19, 0), draft.startTime)
        assertNull(draft.endTime)
        assertEquals("TECH GREEN", draft.location)
    }

    @Test
    fun `parses a relative date with a room number location`() {
        val draft = parse(
            """
            OPEN HOUSE
            TOMORROW AT 6:30 PM
            CULC 144
            """
        )
        assertEquals("Open House", draft.title)
        assertEquals(LocalDate.of(2026, 8, 26), draft.date)
        assertEquals(LocalTime.of(18, 30), draft.startTime)
        assertEquals("CULC 144", draft.location)
    }

    @Test
    fun `prefers the show time over the doors time`() {
        val draft = parse(
            """
            CONCERT
            SEPTEMBER 4
            DOORS 7 PM
            SHOW 8 PM
            THE EASTERN
            """
        )
        assertEquals("Concert", draft.title)
        assertEquals(LocalDate.of(2026, 9, 4), draft.date)
        assertEquals(LocalTime.of(20, 0), draft.startTime)
        assertEquals("THE EASTERN", draft.location)
        assertTrue(draft.description!!.contains("Doors at 7:00"))
    }

    @Test
    fun `parses a weekday with a bare doors time`() {
        val draft = parse(
            """
            MIDNIGHT BREAKFAST
            THIS THURSDAY
            DOORS @ 10
            CULC ROOFTOP
            """
        )
        assertEquals("Midnight Breakfast", draft.title)
        assertEquals(LocalDate.of(2026, 8, 27), draft.date)
        assertEquals(LocalTime.of(22, 0), draft.startTime)
        assertEquals("CULC ROOFTOP", draft.location)
        // A bare hour with no am/pm is a guess, and the draft says so.
        assertEquals(Confidence.MEDIUM, draft.confidence.startTime)
    }

    @Test
    fun `parses a numeric date and a time range`() {
        val draft = parse(
            """
            GENERAL MEETING
            9/2
            6:30-8 PM
            KLAUS 1447
            """
        )
        assertEquals("General Meeting", draft.title)
        assertEquals(LocalDate.of(2026, 9, 2), draft.date)
        assertEquals(LocalTime.of(18, 30), draft.startTime)
        assertEquals(LocalTime.of(20, 0), draft.endTime)
        assertEquals("KLAUS 1447", draft.location)
    }

    // ------------------------------------------------------------------ date formats

    @Test
    fun `understands the common written date formats`() {
        val expected = LocalDate.of(2026, 8, 29)
        listOf(
            "August 29", "Aug 29", "Aug. 29", "August 29th", "Aug 29th",
            "8/29", "08/29", "8/29/26", "08/29/2026",
            "Friday August 29", "Friday, August 29", "Fri Aug 29", "Fri, Aug 29",
            "29 August", "August 29, 2026",
        ).forEach { text ->
            assertEquals("failed on: $text", expected, parse("PARTY\n$text\n8 PM").date)
        }
    }

    @Test
    fun `a day and year survive the month being lost to ocr`() {
        // Today is 2026-08-25, so the next 28th is in August.
        val draft = parse("SOCIAL\nL28.2026\nKENDEDA 230")
        assertEquals(LocalDate.of(2026, 8, 28), draft.date)
        assertEquals(Confidence.LOW, draft.confidence.date)
    }

    @Test
    fun `a day and year rolls into the next month when that day has passed`() {
        assertEquals(LocalDate.of(2026, 9, 3), parse("SOCIAL\n.3.2026\n8 PM").date)
    }

    @Test
    fun `a readable date is preferred over the salvaged one`() {
        val draft = parse("SOCIAL\n8.28.2026\n8 PM")
        assertEquals(LocalDate.of(2026, 8, 28), draft.date)
        assertEquals(Confidence.MEDIUM, draft.confidence.date)
    }

    @Test
    fun `an implausible year is not salvaged`() {
        assertNull(parse("SOCIAL\n28.1999\n8 PM").date)
        assertNull(parse("SOCIAL\n28.2031\n8 PM").date)
    }

    @Test
    fun `understands relative dates`() {
        assertEquals(LocalDate.of(2026, 8, 25), parse("PARTY\ntoday\n8 PM").date)
        assertEquals(LocalDate.of(2026, 8, 25), parse("PARTY\ntonight\n8 PM").date)
        assertEquals(LocalDate.of(2026, 8, 26), parse("PARTY\ntomorrow\n8 PM").date)
    }

    @Test
    fun `resolves weekdays relative to today`() {
        // Today is Tuesday 2026-08-25.
        assertEquals(LocalDate.of(2026, 8, 28), parse("PARTY\nFriday\n8 PM").date)
        assertEquals(LocalDate.of(2026, 8, 28), parse("PARTY\nthis Friday\n8 PM").date)
        assertEquals(LocalDate.of(2026, 9, 4), parse("PARTY\nnext Friday\n8 PM").date)
        // The weekday matching today resolves to today, not a week out.
        assertEquals(LocalDate.of(2026, 8, 25), parse("PARTY\nTuesday\n8 PM").date)
    }

    @Test
    fun `rolls over to next year when the date has already passed`() {
        val newYears = ZonedDateTime.of(LocalDateTime.of(2026, 12, 29, 9, 0), zone)
        assertEquals(LocalDate.of(2027, 1, 3), parse("SHOW\nJanuary 3\n8 PM", newYears).date)
        // A date still ahead of us this year stays in this year.
        assertEquals(LocalDate.of(2026, 12, 31), parse("SHOW\nDecember 31\n8 PM", newYears).date)
    }

    @Test
    fun `keeps an explicit year even when it is in the past`() {
        assertEquals(LocalDate.of(2025, 3, 1), parse("SHOW\nMarch 1, 2025\n8 PM").date)
    }

    @Test
    fun `weekday only dates are flagged as less certain than explicit ones`() {
        assertEquals(Confidence.HIGH, parse("PARTY\nAugust 29\n8 PM").confidence.date)
        assertEquals(Confidence.LOW, parse("PARTY\nnext Friday\n8 PM").confidence.date)
    }

    // ------------------------------------------------------------------ time formats

    @Test
    fun `understands 12 hour and 24 hour start times`() {
        mapOf(
            "7 PM" to LocalTime.of(19, 0),
            "7PM" to LocalTime.of(19, 0),
            "7pm" to LocalTime.of(19, 0),
            "7:00 PM" to LocalTime.of(19, 0),
            "7:30pm" to LocalTime.of(19, 30),
            "19:00" to LocalTime.of(19, 0),
            "19:30" to LocalTime.of(19, 30),
            "7:00 A.M." to LocalTime.of(7, 0),
            "12 AM" to LocalTime.of(0, 0),
            "12 PM" to LocalTime.of(12, 0),
        ).forEach { (text, expected) ->
            assertEquals("failed on: $text", expected, parse("PARTY\nAugust 29\n$text").startTime)
        }
    }

    @Test
    fun `understands time ranges`() {
        mapOf(
            "7-9 PM" to (LocalTime.of(19, 0) to LocalTime.of(21, 0)),
            "7–9 PM" to (LocalTime.of(19, 0) to LocalTime.of(21, 0)),
            "7 PM - 9 PM" to (LocalTime.of(19, 0) to LocalTime.of(21, 0)),
            "7:00 PM – 9:30 PM" to (LocalTime.of(19, 0) to LocalTime.of(21, 30)),
            "19:00-21:00" to (LocalTime.of(19, 0) to LocalTime.of(21, 0)),
            "6:30-8 PM" to (LocalTime.of(18, 30) to LocalTime.of(20, 0)),
            "7 PM to 9 PM" to (LocalTime.of(19, 0) to LocalTime.of(21, 0)),
        ).forEach { (text, expected) ->
            val draft = parse("PARTY\nAugust 29\n$text")
            assertEquals("start failed on: $text", expected.first, draft.startTime)
            assertEquals("end failed on: $text", expected.second, draft.endTime)
        }
    }

    @Test
    fun `understands dotted and dashed numeric dates`() {
        val expected = LocalDate.of(2026, 8, 29)
        listOf("8.29.2026", "08.29.2026", "8.29.26", "8-29-2026", "8-29-26").forEach { text ->
            assertEquals("failed on: $text", expected, parse("PARTY\n$text\n8 PM").date)
        }
    }

    @Test
    fun `a time range survives ocr dropping the colon`() {
        val draft = parse("PARTY\nAugust 29\n6-730 PM")
        assertEquals(LocalTime.of(18, 0), draft.startTime)
        assertEquals(LocalTime.of(19, 30), draft.endTime)
    }

    @Test
    fun `a colon-less number is only a time when something vouches for it`() {
        // Room numbers must not silently become times just because they parse as HHMM.
        assertNull(parse("SEMINAR\nAugust 29\nKLAUS 1447").startTime)
        assertNull(parse("SEMINAR\nAugust 29\nCULC 144").startTime)
        assertNull(parse("SEMINAR\nAugust 29\nROOMS 230-240").startTime)
    }

    @Test
    fun `host app chrome from a screenshot is ignored`() {
        val draft = parse(
            """
            Posts
            somehandle
            SPRING CONCERT
            September 4
            8 PM
            THE EASTERN
            17
            Liked by someone and others
            2 days ago
            somehandle and 2 others
            """
        )
        assertEquals("Spring Concert", draft.title)
        assertEquals("THE EASTERN", draft.location)
    }

    @Test
    fun `a question headline is not used as the title`() {
        val draft = parse(
            """
            HUNGRY?
            FREE PIZZA NIGHT
            September 4
            8 PM
            """
        )
        assertEquals("Free Pizza Night", draft.title)
    }

    @Test
    fun `a range that crosses noon is read as morning to afternoon`() {
        val draft = parse("MARKET\nAugust 29\n11-1 PM")
        assertEquals(LocalTime.of(11, 0), draft.startTime)
        assertEquals(LocalTime.of(13, 0), draft.endTime)
    }

    @Test
    fun `a range that only works by flipping the start is flagged as a guess`() {
        // "6-130 PM" is 6am-1:30pm only if you assume the start is morning. Say so.
        val draft = parse("MARKET\nAugust 29\n6-130 PM")
        assertEquals(LocalTime.of(6, 0), draft.startTime)
        assertEquals(LocalTime.of(13, 30), draft.endTime)
        assertEquals(Confidence.LOW, draft.confidence.startTime)
    }

    @Test
    fun `understands poster time language`() {
        mapOf(
            "doors at 7" to LocalTime.of(19, 0),
            "doors @ 7" to LocalTime.of(19, 0),
            "doors 7 PM" to LocalTime.of(19, 0),
            "starts at 8" to LocalTime.of(20, 0),
            "starts @ 8" to LocalTime.of(20, 0),
            "show at 8" to LocalTime.of(20, 0),
            "7 PM doors" to LocalTime.of(19, 0),
        ).forEach { (text, expected) ->
            assertEquals("failed on: $text", expected, parse("PARTY\nAugust 29\n$text").startTime)
        }
    }

    @Test
    fun `doors and show on one line keeps the show time as the start`() {
        listOf("doors 7 / show 8", "doors 7 PM / show 8 PM", "DOORS 7PM SHOW 8PM").forEach { text ->
            val draft = parse("PARTY\nAugust 29\n$text")
            assertEquals("failed on: $text", LocalTime.of(20, 0), draft.startTime)
            assertTrue("failed on: $text", draft.description!!.contains("7:00"))
        }
    }

    @Test
    fun `a doors time alone becomes the start time`() {
        val draft = parse("PARTY\nAugust 29\ndoors 8 PM")
        assertEquals(LocalTime.of(20, 0), draft.startTime)
        assertNull(draft.endTime)
    }

    @Test
    fun `an explicit meridiem is more trusted than a bare hour`() {
        assertEquals(Confidence.HIGH, parse("PARTY\nAugust 29\n8 PM").confidence.startTime)
        assertEquals(Confidence.MEDIUM, parse("PARTY\nAugust 29\ndoors at 8").confidence.startTime)
        assertEquals(Confidence.LOW, parse("PARTY\nAugust 29\ndoors at 2").confidence.startTime)
    }

    @Test
    fun `room numbers are not mistaken for times`() {
        val draft = parse("SEMINAR\nAugust 29\nKLAUS 1447")
        assertNull(draft.startTime)
        assertEquals("KLAUS 1447", draft.location)
    }

    // -------------------------------------------------------------------- title

    @Test
    fun `metadata lines are not chosen as the title`() {
        val draft = parse(
            """
            PRESENTED BY THE ROBOTICS CLUB
            ROBOT RUMBLE
            SEPTEMBER 12
            8 PM
            REGISTER NOW
            @robotics_club
            www.robotics.example.com
            ${'$'}5 AT THE DOOR
            """
        )
        assertEquals("Robot Rumble", draft.title)
    }

    @Test
    fun `visual prominence beats reading order when layout is available`() {
        val ocr = OcrText(
            raw = "GEORGIA TECH ROBOTICS\nROBOT RUMBLE\nSEPTEMBER 12\n8 PM",
            blocks = listOf(
                block("GEORGIA TECH ROBOTICS", 40, 20, 400, 46),
                block("ROBOT RUMBLE", 30, 120, 700, 240),
                block("SEPTEMBER 12", 40, 500, 380, 534),
                block("8 PM", 40, 560, 220, 594),
            ),
            imageWidth = 800,
            imageHeight = 1000,
        )
        val draft = parser.parse(ParseInput(ocr, defaultNow))
        assertEquals("Robot Rumble", draft.title)
        assertEquals(Confidence.HIGH, draft.confidence.title)
    }

    @Test
    fun `a headline wrapped onto two lines is joined back together`() {
        val ocr = OcrText(
            raw = "TECH SQUARE\nSOCIAL\nAUGUST 28\n7 PM\nTECH GREEN",
            blocks = listOf(
                block("TECH SQUARE", 40, 60, 700, 160),
                block("SOCIAL", 40, 170, 500, 270),
                block("AUGUST 28", 40, 500, 380, 534),
                block("7 PM", 40, 560, 220, 594),
                block("TECH GREEN", 40, 620, 340, 654),
            ),
            imageWidth = 800,
            imageHeight = 1000,
        )
        val draft = parser.parse(ParseInput(ocr, defaultNow))
        assertEquals("Tech Square Social", draft.title)
        assertEquals(LocalDate.of(2026, 8, 28), draft.date)
        assertEquals("TECH GREEN", draft.location)
    }

    @Test
    fun `a differently sized neighbour is not folded into the title`() {
        val ocr = OcrText(
            raw = "ROBOT RUMBLE\nhosted by the robotics club\nAUGUST 28\n7 PM",
            blocks = listOf(
                block("ROBOT RUMBLE", 40, 60, 700, 180),
                block("hosted by the robotics club", 40, 190, 400, 216),
                block("AUGUST 28", 40, 500, 380, 534),
                block("7 PM", 40, 560, 220, 594),
            ),
        )
        assertEquals("Robot Rumble", parser.parse(ParseInput(ocr, defaultNow)).title)
    }

    // ----------------------------------------------------------------- location

    @Test
    fun `a misread digit in a room number is repaired`() {
        assertEquals("KENDEDA 230", parse("TALK\nAugust 29\n8 PM\nKENDEDA Z30").location)
        assertEquals("KLAUS 1447", parse("TALK\nAugust 29\n8 PM\nKLAUS 1447").location)
    }

    @Test
    fun `a real letter in a room number is left alone`() {
        // "B" and the trailing letter here are plausible, so nothing is guessed.
        assertEquals("ROOM 2B", parse("TALK\nAugust 29\n8 PM\nROOM 2B").location)
        assertEquals("BUILDING B12", parse("TALK\nAugust 29\n8 PM\nBUILDING B12").location)
    }

    @Test
    fun `a shouty headline is title cased for an agenda`() {
        assertEquals("Spring Gala", parse("SPRING GALA!!\nAugust 29\n8 PM").title)
        // Short acronyms survive; whole words do not.
        assertEquals("GT Career Fair", parse("GT CAREER FAIR\nAugust 29\n8 PM").title)
    }

    @Test
    fun `deliberate mixed case is left alone`() {
        assertEquals("eXist Dance Showcase", parse("eXist Dance Showcase\nAugust 29\n8 PM").title)
    }

    @Test
    fun `the organiser is prefixed when a handle identifies it`() {
        // "YDSA GT" squashes to "ydsagt", the handle on the same screenshot.
        val draft = parse(
            """
            ydsagt
            WELCOME BACK SOCIAL
            August 29
            8 PM
            YDSA GT
            """
        )
        assertEquals("YDSA GT Welcome Back Social", draft.title)
    }

    @Test
    fun `the organiser is not repeated when the headline already names it`() {
        val draft = parse(
            """
            ydsagt
            YDSA GT WELCOME BACK
            August 29
            8 PM
            YDSA GT
            """
        )
        assertEquals("YDSA GT Welcome Back", draft.title)
    }

    @Test
    fun `a spelled out organisation name becomes an acronym prefix`() {
        val draft = parse(
            """
            YOUNG DEMOCRATIC SOCIALISTS OF AMERICA
            KICK OFF MEETING
            September 3
            6:30 PM
            """
        )
        assertEquals("YDSA Kick Off Meeting", draft.title)
    }

    @Test
    fun `an ordinary phrase is not turned into an acronym`() {
        // No organisation word, so nothing to derive a host from.
        assertEquals("Free Pizza Night", parse("FREE PIZZA NIGHT\nSeptember 3\n6 PM").title)
        // "School" and "students" are too common on flyers to imply the host's name.
        assertEquals(
            "Career Fair",
            parse("George W. Woodruff School\nCAREER FAIR\nSeptember 3\n6 PM").title,
        )
    }

    @Test
    fun `a sponsor credit never supplies the organisation`() {
        val draft = parse("PRESENTED BY THE ROBOTICS CLUB\nROBOT RUMBLE\nSeptember 3\n6 PM")
        assertEquals("Robot Rumble", draft.title)
    }

    @Test
    fun `an unmatched handle never becomes a prefix`() {
        val draft = parse(
            """
            someclub
            SPRING GALA
            August 29
            8 PM
            THE EASTERN
            """
        )
        assertEquals("Spring Gala", draft.title)
    }

    @Test
    fun `explicit location labels win`() {
        listOf(
            "Location: Klaus Atrium",
            "Where: Klaus Atrium",
            "Venue: Klaus Atrium",
        ).forEach { text ->
            val draft = parse("TALK\nAugust 29\n8 PM\n$text")
            assertEquals("failed on: $text", "Klaus Atrium", draft.location)
            assertEquals(Confidence.HIGH, draft.confidence.location)
        }
    }

    @Test
    fun `at prefix is read as a venue`() {
        val draft = parse("TALK\nAugust 29\n8 PM\nat The Eastern")
        assertEquals("The Eastern", draft.location)
    }

    @Test
    fun `urls handles and emails are never treated as locations`() {
        val draft = parse(
            """
            HACK NIGHT
            August 29
            8 PM
            @hacknight_atl
            info@hacknight.example.org
            www.hacknight.example.org
            """
        )
        assertNull(draft.location)
    }

    @Test
    fun `an app name in a screenshot is never the location`() {
        listOf("Instagram", "TikTok", "Messages", "Posts", "instagram.").forEach { chrome ->
            val draft = parse("HACK NIGHT\nAugust 29\n8 PM\n$chrome")
            assertNull("failed on: $chrome", draft.location)
        }
    }

    @Test
    fun `an app name is still fine as part of a real venue name`() {
        assertEquals(
            "Instagram Lounge",
            parse("TALK\nAugust 29\n8 PM\nLocation: Instagram Lounge").location,
        )
    }

    @Test
    fun `poster exclamations are not treated as a location`() {
        assertNull(parse("PARTY\nAugust 29\n8 PM\nFOOD, DRINKS,\nAND GAMES!!").location)
    }

    @Test
    fun `a venue must sit near the date and time`() {
        // Right under the time: believable.
        assertEquals("The Eastern", parse("SHOW\nAugust 29\n8 PM\nThe Eastern").location)
        // Far below, past unrelated text: no longer a venue, just other text.
        val draft = parse(
            """
            SHOW
            August 29
            8 PM
            FOOD, DRINKS
            GOOD VIBES
            ALL WELCOME
            Nina Simone New World Coming
            """
        )
        assertNull(draft.location)
    }

    @Test
    fun `a single stray word is not a venue`() {
        assertNull(parse("PARTY\nAugust 29\n8 PM\nVibes").location)
    }

    @Test
    fun `a call to action is not treated as a location`() {
        val draft = parse("HACK NIGHT\nAugust 29\n8 PM\nREGISTER NOW")
        assertNull(draft.location)
    }

    // -------------------------------------------------------- malformed / partial

    @Test
    fun `empty ocr produces an empty draft`() {
        val draft = parser.parse(ParseInput(OcrText.EMPTY, defaultNow))
        assertEquals(EventDraft.EMPTY, draft)
        assertTrue(draft.isBlank)
    }

    @Test
    fun `whitespace only ocr produces an empty draft`() {
        assertTrue(parse("   \n\n\t  ").isBlank)
    }

    @Test
    fun `garbage ocr does not crash and reports nothing it did not find`() {
        val draft = parse("!!!! ??? ***\n%%%%%\n////")
        assertNull(draft.date)
        assertNull(draft.startTime)
    }

    @Test
    fun `a poster with no date still yields a title and a time`() {
        val draft = parse("BOARD GAME NIGHT\n7 PM\nSTUDENT CENTER")
        assertEquals("Board Game Night", draft.title)
        assertNull(draft.date)
        assertEquals(LocalTime.of(19, 0), draft.startTime)
        assertEquals(Confidence.NONE, draft.confidence.date)
    }

    @Test
    fun `a poster with no time still yields a title and a date`() {
        val draft = parse("CAREER FAIR\nSeptember 15\nEXHIBITION HALL")
        assertEquals("Career Fair", draft.title)
        assertEquals(LocalDate.of(2026, 9, 15), draft.date)
        assertNull(draft.startTime)
        assertEquals(Confidence.NONE, draft.confidence.startTime)
    }

    @Test
    fun `a draft missing key fields is flagged for review`() {
        val draft = parse("SOMETHING\n???")
        assertTrue(draft.confidence.overall.needsReview)
        assertNotNull(draft.title)
    }

    private fun block(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        com.lineup.app.ocr.OcrBlock(
            text = text,
            box = com.lineup.app.ocr.OcrBox(left, top, right, bottom),
            lines = listOf(
                com.lineup.app.ocr.OcrLine(
                    text,
                    com.lineup.app.ocr.OcrBox(left, top, right, bottom),
                )
            ),
        )
}
