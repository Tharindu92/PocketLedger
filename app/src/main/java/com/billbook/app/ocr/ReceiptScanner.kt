package com.billbook.app.ocr

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/** Runs on-device OCR (Google ML Kit) on a bill photo and parses the result. */
class ReceiptScanner(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    data class Result(val text: String, val parsed: ParsedReceipt)

    suspend fun scan(imagePath: String, homeCurrency: String): Result {
        // fromFilePath honours the EXIF rotation of camera photos.
        val image = InputImage.fromFilePath(context, Uri.fromFile(File(imagePath)))
        val visionText = recognizer.process(image).await()
        val lines = visionText.textBlocks.flatMap { it.lines }.map { line ->
            val box = line.boundingBox ?: Rect()
            OcrLine(line.text, box.left, box.top, box.right, box.bottom)
        }
        return Result(visionText.text, ReceiptParser.parse(lines, homeCurrency = homeCurrency))
    }

    fun close() = recognizer.close()
}
