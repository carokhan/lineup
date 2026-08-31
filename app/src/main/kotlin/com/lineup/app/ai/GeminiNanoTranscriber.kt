package com.lineup.app.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.lineup.app.BuildConfig
import com.lineup.app.ocr.OcrText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemini Nano, running on-device through AICore. Nothing leaves the phone and no API key is
 * involved, so the app stays offline and permission-free.
 *
 * Note the platform rule: AICore refuses to run while the app is in the background, so this
 * is only ever called from a visible screen.
 */
class GeminiNanoTranscriber : PosterTranscriber {

    private var model: GenerativeModel? = null

    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    override suspend fun status(): PosterTranscriber.Status = withContext(Dispatchers.IO) {
        runCatching {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> PosterTranscriber.Status.READY
                FeatureStatus.DOWNLOADABLE -> PosterTranscriber.Status.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> PosterTranscriber.Status.DOWNLOADING
                else -> PosterTranscriber.Status.UNSUPPORTED
            }
        }.getOrElse {
            Log.w(TAG, "status check failed: ${it.message}")
            PosterTranscriber.Status.UNSUPPORTED
        }
    }

    override suspend fun warmup() {
        withContext(Dispatchers.IO) {
            runCatching {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                client().warmup()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "TIMING warmup=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
                }
            }.onFailure { Log.w(TAG, "warmup failed: ${it.message}") }
        }
    }

    override suspend fun download(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            var total = 0L
            client().download().collect { update ->
                when (update) {
                    is DownloadStatus.DownloadStarted -> total = update.bytesToDownload
                    is DownloadStatus.DownloadProgress ->
                        if (total > 0) {
                            onProgress((update.totalBytesDownloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    else -> Unit
                }
            }
            status() == PosterTranscriber.Status.READY
        }.getOrElse {
            Log.w(TAG, "download failed: ${it.message}")
            false
        }
    }

    override suspend fun transcribe(bitmap: Bitmap): OcrText? = withContext(Dispatchers.IO) {
        runCatching {
            val request = generateContentRequest(
                ImagePart(bitmap.fitForModel()),
                TextPart(PosterPrompt.TRANSCRIBE),
            ) {
                // Reading a poster is transcription, not writing: no reason to be creative.
                temperature = 0f
                seed = 0
                candidateCount = 1
                // The platform caps this at 256; posters transcribe well inside it.
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val reply = client().generateContent(request).candidates.firstOrNull()?.text
            if (BuildConfig.DEBUG) {
                val took = android.os.SystemClock.elapsedRealtime() - startedAt
                Log.d(TAG, "TIMING generate=${took}ms input=${MAX_INPUT_PX}px chars=${reply?.length ?: 0}")
                Log.d(TAG, "reply=$reply")
            }
            PosterPrompt.cleanTranscription(reply)
        }.getOrElse {
            Log.w(TAG, "transcribe failed: ${it.message}")
            null
        }
    }

    /** Keeps the image within what the on-device model accepts, without distorting it. */
    private fun Bitmap.fitForModel(): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= MAX_INPUT_PX) return this
        val scale = MAX_INPUT_PX.toFloat() / largest
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    fun close() {
        runCatching { model?.close() }
        model = null
    }

    private companion object {
        const val TAG = "LineupNano"
        const val MAX_INPUT_PX = 1536
        const val MAX_OUTPUT_TOKENS = 256
    }
}
