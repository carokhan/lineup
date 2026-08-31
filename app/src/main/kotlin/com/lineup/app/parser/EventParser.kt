package com.lineup.app.parser

import com.lineup.app.core.EventDraft
import com.lineup.app.ocr.OcrText
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Everything a parser is allowed to look at. The clock is injected so parsing is
 * deterministic and testable.
 */
data class ParseInput(
    val ocr: OcrText,
    val now: ZonedDateTime,
) {
    val today: LocalDate get() = now.toLocalDate()
    val currentTime: LocalTime get() = now.toLocalTime()
    val zone: ZoneId get() = now.zone
}

/**
 * The single extension point of the app. A future `LlmEventParser`, or a pipeline that
 * falls back to one when [EventDraft.confidence] is low, plugs in here without the OCR,
 * UI or calendar layers changing.
 */
fun interface EventParser {
    fun parse(input: ParseInput): EventDraft
}
