package com.lineup.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextTest {

    private fun block(text: String, top: Int, left: Int = 0) = OcrBlock(
        text = text,
        box = OcrBox(left, top, left + 100, top + 30),
        lines = listOf(OcrLine(text, OcrBox(left, top, left + 100, top + 30))),
    )

    @Test
    fun `blocks are reordered top to bottom when layout is available`() {
        val ocr = OcrText(
            raw = "irrelevant",
            blocks = listOf(block("THIRD", 300), block("FIRST", 10), block("SECOND", 100)),
        )
        assertEquals(listOf("FIRST", "SECOND", "THIRD"), ocr.lines.map { it.text })
    }

    @Test
    fun `plain text keeps its own order and drops blank lines`() {
        val ocr = OcrText.of("ONE\n\n  TWO  \nTHREE\n")
        assertEquals(listOf("ONE", "TWO", "THREE"), ocr.lines.map { it.text })
    }
}
