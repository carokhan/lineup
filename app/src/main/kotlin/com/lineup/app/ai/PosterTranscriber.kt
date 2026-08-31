package com.lineup.app.ai

import android.graphics.Bitmap
import com.lineup.app.ocr.OcrText

/**
 * A second, stronger recogniser for images the OCR engine cannot read.
 *
 * It deliberately produces [OcrText], not an event: the model is used for what it is
 * genuinely good at - reading hand-lettering off a poster - and the tested deterministic
 * parser still decides what any of it means. Asking the model to do both at once was
 * measurably worse; it invented dates and times it had not read.
 */
interface PosterTranscriber {

    enum class Status {
        /** No on-device model here; the OCR pipeline is all there is. */
        UNSUPPORTED,

        /** Supported, but the model still has to be fetched. Never done without asking. */
        DOWNLOADABLE,

        DOWNLOADING,

        READY,
    }

    suspend fun status(): Status

    /** Loads the model ahead of time so it is not on the critical path. Safe to call twice. */
    suspend fun warmup()

    /** Fetches the model, reporting progress 0..1 where the platform provides it. */
    suspend fun download(onProgress: (Float) -> Unit = {}): Boolean

    /** Returns null when the model declines, errors, or produces nothing usable. */
    suspend fun transcribe(bitmap: Bitmap): OcrText?
}
