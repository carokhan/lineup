package com.lineup.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device Latin text recognition. The model ships inside the APK, so this works offline.
 */
class MlKitOcrService(private val context: Context) : OcrService {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(uri: Uri): OcrText = withContext(Dispatchers.IO) {
        val bitmap = loadBitmap(uri) ?: throw OcrException("That image could not be opened.")
        val result = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (t: Throwable) {
            throw OcrException("Text recognition failed.", t)
        }
        result.toOcrText(bitmap.width, bitmap.height)
    }

    /** Decodes at a bounded size and applies the EXIF orientation the camera recorded. */
    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return@runCatching null

        var sample = 1
        while (largest / sample > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return@runCatching null

        val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) }
        val degrees = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees == 0) decoded else decoded.rotated(degrees).also { decoded.recycle() }
    }.getOrNull()

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private companion object {
        /** Plenty of detail for text, and keeps a large screenshot from blowing up memory. */
        const val MAX_DIMENSION = 2560
    }
}

private fun Text.toOcrText(width: Int, height: Int): OcrText = OcrText(
    raw = text,
    blocks = textBlocks.map { block ->
        OcrBlock(
            text = block.text,
            box = block.boundingBox?.toOcrBox(),
            lines = block.lines.map { line -> OcrLine(line.text, line.boundingBox?.toOcrBox()) },
        )
    },
    imageWidth = width,
    imageHeight = height,
)

private fun android.graphics.Rect.toOcrBox() = OcrBox(left, top, right, bottom)
