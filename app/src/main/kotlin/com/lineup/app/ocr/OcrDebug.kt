package com.lineup.app.ocr

import android.util.Log
import com.lineup.app.BuildConfig

/**
 * Debug-only dump of the OCR layout, so a misparsed poster can be turned into a regression
 * fixture straight from `adb logcat -s LineupOcr`. Compiled out of release builds.
 */
object OcrDebug {

    private const val TAG = "LineupOcr"

    fun dump(ocr: OcrText) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "BEGIN image=${ocr.imageWidth}x${ocr.imageHeight} lines=${ocr.lines.size}")
        ocr.lines.forEach { line ->
            val b = line.box
            val box = if (b == null) "null" else "${b.left},${b.top},${b.right},${b.bottom}"
            Log.d(TAG, "LINE\t$box\t${line.text.replace('\n', ' ')}")
        }
        Log.d(TAG, "END")
    }

    fun dumpDraft(draft: Any) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "DRAFT $draft")
    }
}
