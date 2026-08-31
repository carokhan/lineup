package com.lineup.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedEventCodecTest {

    private val event = SavedEvent(
        eventId = 1040,
        title = "YDSA GT Welcome Back Social",
        startMillis = 1787954400000,
        calendarName = "GT Events",
        savedAtMillis = 1787900000000,
    )

    @Test
    fun `round trips a list`() {
        val events = listOf(event, event.copy(eventId = 7, title = "Other", startMillis = null))
        assertEquals(events, SavedEventCodec.decode(SavedEventCodec.encode(events)))
    }

    @Test
    fun `an event with no start time survives`() {
        val encoded = SavedEventCodec.encode(listOf(event.copy(startMillis = null)))
        assertNull(SavedEventCodec.decode(encoded).single().startMillis)
    }

    @Test
    fun `empty and malformed input yield nothing rather than throwing`() {
        assertTrue(SavedEventCodec.decode(null).isEmpty())
        assertTrue(SavedEventCodec.decode("").isEmpty())
        assertTrue(SavedEventCodec.decode("garbage").isEmpty())
        assertTrue(SavedEventCodec.decode("notanumber\u001Fx\u001Fy\u001Fz\u001Fw").isEmpty())
    }

    @Test
    fun `a title containing the delimiters cannot corrupt the record`() {
        val nasty = event.copy(title = "Party\u001Fwith\u001Etext")
        val decoded = SavedEventCodec.decode(SavedEventCodec.encode(listOf(nasty))).single()
        // The separators are stripped rather than allowed to split the record.
        assertEquals("Partywithtext", decoded.title)
        assertEquals(event.eventId, decoded.eventId)
        assertEquals(event.calendarName, decoded.calendarName)
    }

    @Test
    fun `newlines in a title are preserved rather than splitting records`() {
        val encoded = SavedEventCodec.encode(listOf(event.copy(title = "a\nb")))
        assertEquals("a\nb", SavedEventCodec.decode(encoded).single().title)
    }
}
