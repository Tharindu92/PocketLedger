package com.billbook.app.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.billbook.app.ocr.OcrLine
import com.billbook.app.ocr.ReceiptParser
import com.billbook.app.util.Limits
import com.billbook.app.util.copyToLimited
import com.google.mlkit.vision.common.InputImage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Opens a statement file the user picked: CSV is parsed directly, PDF pages are read with OCR. */
class StatementReader(private val context: Context) {

    enum class Kind { CSV, PDF }

    /** Copies the picked file into the cache (picked Uris can stop being readable) and detects its type. */
    suspend fun open(uri: Uri): Pair<File, Kind> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "statement")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
        try {
            input.use { i -> file.outputStream().use { i.copyToLimited(it, Limits.STATEMENT_BYTES) } }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        val head = file.inputStream().use { s -> ByteArray(5).also { s.read(it) } }
        file to if (String(head) == "%PDF-") Kind.PDF else Kind.CSV
    }

    suspend fun readCsv(file: File, fallbackCurrency: String, columns: BankCsvParser.Columns? = null): StatementParseResult =
        withContext(Dispatchers.IO) {
            val bytes = file.readBytes()
            // Most banks export UTF-8; some German banks still use Windows-1252.
            val utf8 = String(bytes, Charsets.UTF_8)
            val text = if (utf8.contains('�')) String(bytes, charset("windows-1252")) else utf8
            BankCsvParser.parse(text, fallbackCurrency, columns)
        }

    /**
     * Reads the text stored in the PDF (exact, fast); only scanned PDFs without text are rendered
     * and read with OCR. [onPage] reports OCR progress.
     */
    suspend fun readPdf(file: File, fallbackCurrency: String, onPage: (Int, Int) -> Unit): StatementParseResult {
        extractRows(file)?.let { rows -> return BankPdfParser.parseRows(rows, fallbackCurrency) }
        return readPdfWithOcr(file, fallbackCurrency, onPage)
    }

    /**
     * Rows of a digital PDF with each word's position, or null when it holds (almost) no text, i.e.
     * it is a scan or photo.
     */
    private suspend fun extractRows(file: File): List<PdfRow>? = withContext(Dispatchers.IO) {
        try {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(file).use { doc ->
                val pages = minOf(doc.numberOfPages, MAX_PAGES)
                val stripper = WordCollector().apply {
                    sortByPosition = true   // rows top to bottom, words left to right
                    startPage = 1
                    endPage = pages
                }
                stripper.getText(doc)
                val chars = stripper.words.sumOf { w -> w.text.count { it.isLetterOrDigit() } }
                // A scanned page has no text layer; a statement page has hundreds of characters.
                if (chars < MIN_TEXT_CHARS_PER_PAGE * pages) return@withContext null
                PdfLayout.rows(stripper.words)
            }
        } catch (e: IOException) {
            null   // damaged or password-protected: try OCR, which fails with a clear message if needed
        }
    }

    /** Collects every word with its position instead of writing plain text. */
    private class WordCollector : PDFTextStripper() {
        val words = mutableListOf<PdfWord>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isBlank() || textPositions.isEmpty()) return
            val first = textPositions.first()
            val last = textPositions.last()
            words += PdfWord(
                text = text,
                page = currentPageNo,
                x0 = first.xDirAdj,
                x1 = last.xDirAdj + last.widthDirAdj,
                y = first.yDirAdj,
                height = textPositions.maxOf { it.heightDir }.coerceAtLeast(1f),
            )
        }
    }

    private suspend fun readPdfWithOcr(file: File, fallbackCurrency: String, onPage: (Int, Int) -> Unit): StatementParseResult {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val rows = mutableListOf<PdfRow>()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val pages = minOf(renderer.pageCount, MAX_PAGES)
                    for (index in 0 until pages) {
                        onPage(index + 1, pages)
                        val bitmap = withContext(Dispatchers.IO) { render(renderer, index) }
                        try {
                            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                            val lines = text.textBlocks.flatMap { it.lines }.map { l ->
                                val b = l.boundingBox ?: Rect()
                                OcrLine(l.text, b.left, b.top, b.right, b.bottom)
                            }
                            // OCR lines keep their boxes, so column positions work for scans too.
                            rows += ReceiptParser.groupLines(lines).map { line ->
                                PdfRow(line.map { PdfCell(it.text, it.left.toFloat(), it.right.toFloat()) })
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            return BankPdfParser.parseRows(rows, fallbackCurrency, source = BankPdfParser.SOURCE_OCR)
        } finally {
            recognizer.close()
        }
    }

    private fun render(renderer: PdfRenderer, index: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            // ~200 dpi for an A4 page: sharp enough for small statement print.
            val width = RENDER_WIDTH
            val height = (width.toFloat() * page.height / page.width).toInt()
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            }
        } finally {
            page.close()
        }
    }

    private companion object {
        const val MAX_PAGES = 40
        const val RENDER_WIDTH = 1650
        const val MIN_TEXT_CHARS_PER_PAGE = 30
    }
}
