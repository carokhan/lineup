package com.lineup.app.ocr

import android.net.Uri

/**
 * Turns an image into text. Kept as an interface so the recognizer can be swapped or faked
 * without touching the ViewModel.
 *
 * This is also the seam a vision model would slot into: the failures that matter on
 * hand-lettered flyers are recognition failures, upstream of the parser.
 */
interface OcrService {
    suspend fun recognize(uri: Uri): OcrText
}

class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause)
