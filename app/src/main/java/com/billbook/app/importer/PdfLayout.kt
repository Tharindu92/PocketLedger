package com.billbook.app.importer

/** A run of text on one line of a page, with its horizontal extent (any unit, same per page). */
data class PdfCell(val text: String, val x0: Float, val x1: Float)

/** One visual line of a statement page, cells left to right. */
data class PdfRow(val cells: List<PdfCell>) {
    /** Cells separated by wide gaps, as the regex-based parsing expects. */
    val text: String = cells.joinToString("   ") { it.text.trim() }

    companion object {
        /** A row whose layout is unknown (plain text): column detection is skipped for it. */
        fun plain(text: String) = PdfRow(listOf(PdfCell(text, 0f, 0f)))
    }
}

/** A word with its position on the page, from the PDF text layer or OCR. */
data class PdfWord(val text: String, val page: Int, val x0: Float, val x1: Float, val y: Float, val height: Float)

object PdfLayout {

    /**
     * Groups words into rows (same line on the same page) and joins neighbouring words into cells.
     * A gap wider than about one letter height starts a new cell, i.e. a new column.
     */
    fun rows(words: List<PdfWord>): List<PdfRow> {
        val result = mutableListOf<PdfRow>()
        for ((_, pageWords) in words.filter { it.text.isNotBlank() }.groupBy { it.page }.toSortedMap()) {
            val lines = mutableListOf<MutableList<PdfWord>>()
            for (w in pageWords.sortedBy { it.y }) {
                val line = lines.lastOrNull()
                val tolerance = maxOf(w.height, line?.first()?.height ?: 0f) * 0.5f
                if (line != null && kotlin.math.abs(line.first().y - w.y) <= tolerance) line += w else lines += mutableListOf(w)
            }
            for (line in lines) {
                val sorted = line.sortedBy { it.x0 }
                val cells = mutableListOf<PdfCell>()
                var text = StringBuilder(sorted.first().text)
                var x0 = sorted.first().x0
                var x1 = sorted.first().x1
                for (w in sorted.drop(1)) {
                    if (w.x0 - x1 > maxOf(w.height, 4f) * 0.9f) {
                        cells += PdfCell(text.toString(), x0, x1)
                        text = StringBuilder(w.text)
                        x0 = w.x0
                    } else {
                        text.append(' ').append(w.text)
                    }
                    x1 = maxOf(x1, w.x1)
                }
                cells += PdfCell(text.toString(), x0, x1)
                result += PdfRow(cells)
            }
        }
        return result
    }
}
