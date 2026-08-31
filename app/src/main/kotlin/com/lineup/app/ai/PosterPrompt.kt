package com.lineup.app.ai

import com.lineup.app.ocr.OcrText

/**
 * The transcription prompt and the reply cleaner, free of Android and ML Kit types so both
 * can be unit tested.
 */
internal object PosterPrompt {

    /**
     * Transcription only - the model is a reader here, not an extractor.
     *
     * Restricting it to the poster artwork is a speed decision as much as a quality one:
     * generation dominates the latency, and transcribing a screenshot's whole interface
     * roughly doubles the tokens for text the parser discards as app chrome anyway.
     */
    val TRANSCRIBE: String =
        "Transcribe the event poster in this screenshot. " +
            "Skip app interface: usernames, captions, comments, counts, timestamps. " +
            "One line per line, copying dates and times exactly. Poster text only."

    private val PREAMBLE = Regex(
        """^\s*(sure|certainly|of course|here('s| is)|the text (in|reads)|okay|ok)\b.*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    private val FENCE = Regex("""^\s*```.*$""", RegexOption.MULTILINE)

    private val REFUSAL = Regex(
        """^\s*(i'?m sorry|i am sorry|i can'?t|i cannot|i'?m unable|sorry,|as an ai)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Strips the scaffolding a chat-tuned model wraps around a transcription. Returns null
     * when nothing usable survives, so a refusal never reaches the parser as poster text.
     */
    fun cleanTranscription(reply: String?): OcrText? {
        if (reply.isNullOrBlank()) return null
        val cleaned = reply
            .replace(FENCE, "")
            .replace(PREAMBLE, "")
            .lines()
            .map { line -> line.trim().removePrefix("* ").removePrefix("- ").trim() }
            .filter { it.isNotBlank() }

        // A poster is always several short lines. One line back means a refusal or a
        // one-sentence description, never a transcription worth parsing.
        if (cleaned.size < 2) return null
        if (REFUSAL.containsMatchIn(cleaned.first())) return null
        // Prose: every line a full sentence, no poster-shaped short lines at all.
        if (cleaned.all { it.length > 80 }) return null
        return OcrText.of(cleaned.joinToString("\n"))
    }
}
