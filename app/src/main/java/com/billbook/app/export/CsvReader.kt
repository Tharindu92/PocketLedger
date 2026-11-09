package com.billbook.app.export

/** Reads CSV files (RFC 4180 quoting, optional UTF-8 BOM): this app's exports and bank statements. */
object CsvReader {

    /** Comma, semicolon (common in German bank exports) or tab, whichever the first lines use most. */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(15).joinToString("\n")
        return listOf(',', ';', '\t').maxBy { d -> sample.count { it == d } }
    }

    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> cell.append(c)
                c == delimiter -> { row += cell.toString(); cell.clear() }
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    rows += row; row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
