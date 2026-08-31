package com.lineup.app.ocr

/**
 * Internal, framework-agnostic representation of an OCR result.
 *
 * Nothing downstream of the OCR service should ever touch an ML Kit type; swapping the
 * recognizer means writing a new [OcrService] that produces one of these.
 */
data class OcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerY: Int get() = (top + bottom) / 2
}

data class OcrLine(
    val text: String,
    val box: OcrBox? = null,
)

data class OcrBlock(
    val text: String,
    val box: OcrBox? = null,
    val lines: List<OcrLine> = emptyList(),
)

data class OcrText(
    val raw: String,
    val blocks: List<OcrBlock> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
) {
    /**
     * Flattened reading order. ML Kit returns blocks in no particular order, so when every
     * line carries layout information they are sorted top-to-bottom, left-to-right.
     * Falls back to splitting [raw] when no layout info exists.
     */
    val lines: List<OcrLine> by lazy {
        if (blocks.isEmpty()) {
            raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { OcrLine(it) }
        } else {
            val flat = blocks.flatMap { block ->
                if (block.lines.isEmpty()) listOf(OcrLine(block.text, block.box)) else block.lines
            }.filter { it.text.isNotBlank() }
            if (flat.isNotEmpty() && flat.all { it.box != null }) {
                flat.sortedWith(compareBy({ it.box!!.top }, { it.box!!.left }))
            } else {
                flat
            }
        }
    }

    val isEmpty: Boolean get() = raw.isBlank() && lines.isEmpty()

    companion object {
        val EMPTY = OcrText(raw = "")

        /** Convenience for tests and for pasted/manual text: no layout, reading order only. */
        fun of(text: String): OcrText = OcrText(raw = text.trimIndent())
    }
}
