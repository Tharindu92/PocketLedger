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
import com.google.mlkit.vision.common.InputImage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
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
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
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
        extractText(file)?.let { rows -> return BankPdfParser.parse(rows, fallbackCurrency) }
        return readPdfWithOcr(file, fallbackCurrency, onPage)
    }

    /**
     * Text rows of a digital PDF in reading order, or null when it holds (almost) no text, i.e. it
     * is a scan or photo.
     */
    private suspend fun extractText(file: File): List<String>? = withContext(Dispatchers.IO) {
        try {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(file).use { doc ->
                val pages = minOf(doc.numberOfPages, MAX_PAGES)
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true   // rows top to bottom, words left to right
                    startPage = 1
                    endPage = pages
                }
                val text = stripper.getText(doc)
                // A scanned page has no text layer; a statement page has hundreds of characters.
                if (text.count { it.isLetterOrDigit() } < MIN_TEXT_CHARS_PER_PAGE * pages) return@withContext null
                text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        } catch (e: IOException) {
            null   // damaged or password-protected: try OCR, which fails with a clear message if needed
        }
    }

    private suspend fun readPdfWithOcr(file: File, fallbackCurrency: String, onPage: (Int, Int) -> Unit): StatementParseResult {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val rows = mutableListOf<String>()
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
                            rows += ReceiptParser.groupIntoRows(lines)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            return BankPdfParser.parse(rows, fallbackCurrency, source = BankPdfParser.SOURCE_OCR)
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
