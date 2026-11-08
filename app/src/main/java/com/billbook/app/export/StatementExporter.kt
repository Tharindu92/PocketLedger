package com.billbook.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import com.billbook.app.data.date
import com.billbook.app.data.signedCents
import com.billbook.app.util.Money
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class ExportFormat(val mime: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    CSV("text/csv", "csv"),
}

/** Everything needed to render a statement for one period. */
class StatementData(
    val from: LocalDate,
    val to: LocalDate,
    val currency: String,
    val businessName: String,
    items: List<Transaction>,
    /** Profile the statement is for, e.g. "Personal" or "Cricket Club". */
    val profileName: String = "",
) {
    val transactions: List<Transaction> = items.sortedWith(compareBy({ it.dateEpochDay }, { it.id }))
    val totalIncome: Long = transactions.filter { it.type == TxType.INCOME }.sumOf { it.amountCents }
    val totalExpense: Long = transactions.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents }
    val taxOnExpenses: Long = transactions.filter { it.type == TxType.EXPENSE }.sumOf { it.taxCents }
    val net: Long get() = totalIncome - totalExpense

    fun byCategory(type: TxType): List<Pair<String, Long>> =
        transactions.filter { it.type == type }
            .groupBy { it.category.ifBlank { "Uncategorised" } }
            .map { (category, list) -> category to list.sumOf { it.amountCents } }
            .sortedByDescending { it.second }
}

object StatementExporter {

    fun fileName(profileName: String, from: LocalDate, to: LocalDate, currency: String, format: ExportFormat): String {
        val profile = profileName.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "Statement" }
        return "${profile}_${currency}_${from}_to_$to.${format.extension}"
    }

    fun write(data: StatementData, format: ExportFormat, out: OutputStream) = when (format) {
        ExportFormat.PDF -> PdfStatementWriter(data).write(out)
        ExportFormat.CSV -> writeCsv(data, out)
    }

    private fun writeCsv(data: StatementData, out: OutputStream) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("﻿") // BOM so Excel opens UTF-8 correctly
        fun row(vararg cells: String) {
            writer.write(cells.joinToString(",") { escape(it) })
            writer.write("\r\n")
        }
        row(
            "Profile", "Date", "Type", "Business / Party", "Bill Number", "Category", "Payment Method",
            "Amount", "Signed Amount", "Tax", "Currency", "Notes",
        )
        data.transactions.forEach { t ->
            row(
                data.profileName,
                t.date.toString(),
                if (t.type == TxType.INCOME) "Income" else "Expense",
                t.party,
                t.billNumber,
                t.category,
                t.paymentMethod,
                Money.toInput(t.amountCents),
                Money.toInput(t.signedCents),
                Money.toInput(t.taxCents),
                t.currency.ifBlank { data.currency },
                t.notes,
            )
        }
        writer.flush()
    }

    private fun escape(value: String): String {
        // Neutralise spreadsheet formulas in free-text cells.
        val safe = if (value.isNotEmpty() && value[0] in "=+@") "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}

/** Draws an A4 statement with summary, category breakdown and a transaction ledger. */
private class PdfStatementWriter(private val data: StatementData) {

    private data class Col(val title: String, val width: Float, val right: Boolean = false)

    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 36f
    private val contentWidth = pageWidth - 2 * margin
    private val bottomLimit = pageHeight - margin - 22f
    private val rowHeight = 13f

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var pageNumber = 0
    private var y = 0f

    private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy")
    private val shortDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.BLACK, right: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.color = color
            textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        }

    private val green = 0xFF2E7D32.toInt()
    private val red = 0xFFC62828.toInt()
    private val grey = 0xFF5F6B7A.toInt()

    private val titlePaint = paint(18f, bold = true)
    private val headingPaint = paint(12f, bold = true)
    private val bodyPaint = paint(9f)
    private val bodyRight = paint(9f, right = true)
    private val boldPaint = paint(9f, bold = true)
    private val boldRight = paint(9f, bold = true, right = true)
    private val mutedPaint = paint(8f, color = grey)
    private val mutedRight = paint(8f, color = grey, right = true)
    private val incomeRight = paint(9f, color = green, right = true)
    private val expenseRight = paint(9f, color = red, right = true)
    private val bigPaint = paint(13f, bold = true)
    private val bigIncome = paint(13f, bold = true, color = green)
    private val bigExpense = paint(13f, bold = true, color = red)

    private val linePaint = Paint().apply { color = 0xFFB0B7C3.toInt(); strokeWidth = 0.6f }
    private val headerFill = Paint().apply { color = 0xFFE3EAF2.toInt(); style = Paint.Style.FILL }
    private val boxFill = Paint().apply { color = 0xFFF1F4F8.toInt(); style = Paint.Style.FILL }
    private val zebraFill = Paint().apply { color = 0xFFF7F9FB.toInt(); style = Paint.Style.FILL }
    private val accentFill = Paint().apply { color = 0xFF1B6B50.toInt(); style = Paint.Style.FILL }

    private val ledgerColumns = listOf(
        Col("Date", 56f),
        Col("Business / party", 128f),
        Col("Bill no.", 64f),
        Col("Category", 84f),
        Col("Payment", 63f),
        Col("Amount", 64f, right = true),
        Col("Balance", 64f, right = true),
    )

    fun write(out: OutputStream) {
        try {
            newPage()
            header()
            summary()
            categoryTable("Income by category", data.byCategory(TxType.INCOME), data.totalIncome)
            categoryTable("Expenses by category", data.byCategory(TxType.EXPENSE), data.totalExpense)
            ledger()
            finishPage()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    // ------------------------------------------------------------ pages

    private fun newPage() {
        finishPage()
        pageNumber++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        page = p
        canvas = p.canvas
        y = margin
    }

    private fun finishPage() {
        val p = page ?: return
        val footerY = pageHeight - margin + 4f
        canvas.drawLine(margin, footerY - 12f, pageWidth - margin, footerY - 12f, linePaint)
        canvas.drawText("Generated by Pocket Ledger on $generatedAt", margin, footerY, mutedPaint)
        canvas.drawText("Page $pageNumber", pageWidth - margin, footerY, mutedRight)
        doc.finishPage(p)
        page = null
    }

    private fun ensureSpace(height: Float): Boolean {
        if (y + height <= bottomLimit) return false
        newPage()
        return true
    }

    /** Draws text with its top at the current y. [x] is the right edge for right-aligned paints. */
    private fun text(value: String, x: Float, p: TextPaint, maxWidth: Float? = null) {
        val shown = if (maxWidth != null) {
            TextUtils.ellipsize(value, p, maxWidth, TextUtils.TruncateAt.END).toString()
        } else {
            value
        }
        canvas.drawText(shown, x, y - p.ascent(), p)
    }

    // --------------------------------------------------------- sections

    private fun header() {
        val top = y
        val x = margin + 12f
        if (data.businessName.isNotBlank()) {
            text(data.businessName, x, headingPaint, contentWidth - 12f)
            y += 17f
        }
        text("Statement of Income and Expenses", x, titlePaint)
        y += 24f
        if (data.profileName.isNotBlank()) {
            text("Account: ${data.profileName}", x, headingPaint, contentWidth - 12f)
            y += 17f
        }
        text(
            "Period: ${data.from.format(dateFormat)} – ${data.to.format(dateFormat)}    " +
                "Currency: ${data.currency}    Entries: ${data.transactions.size}",
            x, bodyPaint,
        )
        y += 14f
        canvas.drawRect(margin, top, margin + 4f, y, accentFill)
        y += 14f
    }

    private fun summary() {
        val netLabel = if (data.net >= 0) "Net surplus" else "Net deficit"
        val boxes = listOf(
            Triple("Total income", data.totalIncome, bigIncome),
            Triple("Total expenses", data.totalExpense, bigExpense),
            Triple(netLabel, data.net, if (data.net >= 0) bigIncome else bigExpense),
            Triple("Tax paid on expenses", data.taxOnExpenses, bigPaint),
        )
        val gap = 8f
        val boxWidth = (contentWidth - gap * (boxes.size - 1)) / boxes.size
        val boxHeight = 46f
        ensureSpace(boxHeight + 20f)
        boxes.forEachIndexed { i, (label, value, valuePaint) ->
            val left = margin + i * (boxWidth + gap)
            canvas.drawRoundRect(left, y, left + boxWidth, y + boxHeight, 4f, 4f, boxFill)
            canvas.drawText(label, left + 8f, y + 16f, mutedPaint)
            val shown = TextUtils.ellipsize(Money.format(value), valuePaint, boxWidth - 16f, TextUtils.TruncateAt.END)
            canvas.drawText(shown.toString(), left + 8f, y + 36f, valuePaint)
        }
        y += boxHeight + 20f
    }

    private fun categoryTable(title: String, rows: List<Pair<String, Long>>, total: Long) {
        if (rows.isEmpty()) return
        val columns = listOf(Col("Category", contentWidth - 160f), Col("Amount", 100f, true), Col("Share", 60f, true))
        ensureSpace(18f + 16f + rowHeight * 2)
        text(title, margin, headingPaint)
        y += 18f
        tableHeader(columns)
        rows.forEach { (category, amount) ->
            if (ensureSpace(rowHeight)) tableHeader(columns)
            val share = if (total == 0L) "" else "%.1f%%".format(amount * 100.0 / total)
            drawRow(columns, listOf(category to bodyPaint, Money.format(amount) to bodyRight, share to bodyRight))
            y += rowHeight
        }
        ensureSpace(rowHeight + 4f)
        canvas.drawLine(margin, y, margin + contentWidth, y, linePaint)
        y += 3f
        drawRow(columns, listOf("Total" to boldPaint, Money.format(total) to boldRight, "" to boldRight))
        y += rowHeight + 14f
    }

    private fun ledger() {
        ensureSpace(18f + 16f + rowHeight)
        text("Transactions", margin, headingPaint)
        y += 18f
        if (data.transactions.isEmpty()) {
            text("No transactions in this period.", margin, mutedPaint)
            y += rowHeight
            return
        }
        tableHeader(ledgerColumns)
        var balance = 0L
        data.transactions.forEachIndexed { index, t ->
            if (ensureSpace(rowHeight)) tableHeader(ledgerColumns)
            if (index % 2 == 1) canvas.drawRect(margin, y - 2f, margin + contentWidth, y + rowHeight - 2f, zebraFill)
            balance += t.signedCents
            drawRow(
                ledgerColumns,
                listOf(
                    t.date.format(shortDate) to bodyPaint,
                    t.party.ifBlank { "—" } to bodyPaint,
                    t.billNumber to bodyPaint,
                    t.category to bodyPaint,
                    t.paymentMethod to bodyPaint,
                    Money.format(t.signedCents) to (if (t.type == TxType.INCOME) incomeRight else expenseRight),
                    Money.format(balance) to bodyRight,
                ),
            )
            y += rowHeight
        }

        ensureSpace(rowHeight * 3 + 8f)
        canvas.drawLine(margin, y, margin + contentWidth, y, linePaint)
        y += 4f
        val labelRight = margin + contentWidth - 64f - 3f
        listOf(
            "Total income" to data.totalIncome,
            "Total expenses" to -data.totalExpense,
            "Closing balance (net)" to data.net,
        ).forEach { (label, value) ->
            text(label, labelRight - 70f, boldRight)
            text(Money.format(value), margin + contentWidth - 3f, boldRight)
            y += rowHeight
        }
    }

    private fun tableHeader(columns: List<Col>) {
        canvas.drawRect(margin, y - 2f, margin + contentWidth, y + rowHeight + 1f, headerFill)
        drawRow(columns, columns.map { it.title to if (it.right) boldRight else boldPaint })
        y += rowHeight + 3f
    }

    private fun drawRow(columns: List<Col>, cells: List<Pair<String, TextPaint>>) {
        val pad = 3f
        var x = margin
        columns.forEachIndexed { i, col ->
            val (value, p) = cells[i]
            val anchor = if (col.right) x + col.width - pad else x + pad
            text(value, anchor, p, col.width - 2 * pad)
            x += col.width
        }
    }
}
