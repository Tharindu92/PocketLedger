package com.billbook.app.importer

import com.billbook.app.ocr.ReceiptParser
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds bookings in the rows of a PDF statement. A booking is a row with an amount, dated by a date
 * on the same row or above it.
 *
 * When the statement has a column header ("Paid out | Paid in | Balance", "Soll | Haben",
 * "Betrag | Saldo"), each amount is placed in the column it sits under. That gives the direction even
 * where no sign is printed, keeps balances out, and lets one date head several bookings. Without a
 * header the sign comes from the text (-, +, Soll/Haben, Credit…), and guessed rows are marked
 * [StatementLine.uncertain].
 *
 * Credit card statements (e.g. American Express) are read the other way round from bank accounts:
 * a plain amount is a charge, a minus or "CR" a refund, and the card bill payment is skipped.
 */
object BankPdfParser {

    // An amount with exactly two decimals, optional sign/currency; not part of a date ("12.05.2024").
    private val AMOUNT = Regex(
        """(?<![\d.,])([-+−]\s?)?(?:€|EUR|\$|£)?\s?(\d{1,3}(?:[.,\s]\d{3})*[.,]\d{2})(?![.,]?\d)(\s?[-−]|\s?CR\b)?(\s?(?:€|EUR))?"""
    )
    private val DATE_IN_ROW = Regex(
        """(?<!\d)(\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[./]\d{1,2}[./](?:\d{4}|\d{2})|\d{1,2}\.?\s*(?:jan|feb|mär|mar|mrz|apr|mai|may|jun|jul|aug|sep|okt|oct|nov|dez|dec)[a-zä]*\.?(?:\s*\d{4})?)(?!\d)""",
        RegexOption.IGNORE_CASE,
    )
    /** "18.09   19.09   KAUFLAND …": purchase and booking day without a year, at the start of a row. */
    private val LEADING_SHORT_DATES = Regex("""^\s*(\d{1,2})\.(\d{1,2})\.?(?=\s)(?:\s+(\d{1,2})\.(\d{1,2})\.?(?=\s))?""")
    private val SKIP = Regex(
        """\b(balance|saldo|kontostand|opening|closing|übertrag|carried forward|brought forward|total|summe|""" +
            """zwischensumme|page|seite|iban|bic|statement|kontoauszug|account number|kontonummer)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DEBIT_WORD = Regex("""\b(S|Soll|Dr|Debit|Lastschrift|Belastung|Kartenzahlung|Card payment)\b""")
    private val CREDIT_WORD = Regex("""\b(H|Haben|Cr|Credit|Gutschrift|Incoming|Eingang)\b""")
    private val EURO = Regex("""€|\bEUR\b""")
    // Wording of the statement itself, not of a booking: an N26 statement may well list a payment to
    // "American Express" without being a card statement.
    private val CARD_STATEMENT = Regex(
        """karten-nr\.?|kreditkartenabrechnung|credit card statement|account ending""",
        RegexOption.IGNORE_CASE,
    )
    /** The card bill being paid from the bank account: already recorded there, so not a booking. */
    private val CARD_BILL_PAYMENT = Regex(
        """zahlung erhalten|vielen dank für ihre zahlung|payment received|thank you for your payment|lastschrifteinzug|autopay""",
        RegexOption.IGNORE_CASE,
    )
    private val FOREIGN_CODE = Regex("""\b(${ReceiptParser.ISO_CODES.joinToString("|")})\b""")

    // ------------------------------------------------------------ column headers

    private enum class Column { DEBIT, CREDIT, BALANCE, AMOUNT, FOREIGN }

    private data class HeaderColumn(val column: Column, val x0: Float, val x1: Float)

    private val CURRENCY_IN_HEADER = Regex("""\((?:eur|€|usd|gbp|lkr|chf)\)|\bin\s+eur\b|\beur\b|€""")
    private val FOREIGN_HEADER = Regex("""fremdwährung|foreign|original""")
    private val DEBIT_HEADER = Regex(
        """paid out|money out|out|debits?|withdrawals?|soll|ausgang|ausgänge|ausgaben|belastungen?|lastschriften?|abbuchungen?|auszahlungen?"""
    )
    private val CREDIT_HEADER = Regex(
        """paid in|money in|in|credits?|deposits?|haben|eingang|eingänge|einnahmen|gutschriften?|einzahlungen?"""
    )
    private val BALANCE_HEADER = Regex("""balance|running balance|saldo|neuer saldo|kontostand""")
    private val AMOUNT_HEADER = Regex("""amount|betrag|umsatz|value|wert""")

    private fun headerColumn(cell: PdfCell): HeaderColumn? {
        val name = CURRENCY_IN_HEADER.replace(cell.text.lowercase(), " ").replace(Regex("""\s+"""), " ").trim()
        val column = when {
            name.isEmpty() -> null
            FOREIGN_HEADER.containsMatchIn(name) -> Column.FOREIGN
            DEBIT_HEADER.matches(name) -> Column.DEBIT
            CREDIT_HEADER.matches(name) -> Column.CREDIT
            BALANCE_HEADER.matches(name) -> Column.BALANCE
            AMOUNT_HEADER.matches(name) -> Column.AMOUNT
            else -> null
        } ?: return null
        return HeaderColumn(column, cell.x0, cell.x1)
    }

    /** Header row: no amounts, and at least two cells naming money columns. */
    private fun headerColumns(row: PdfRow): List<HeaderColumn>? {
        if (row.cells.size < 2) return null
        val found = row.cells.mapNotNull(::headerColumn)
        return found.takeIf { f -> f.size >= 2 && f.any { it.column != Column.FOREIGN } }
    }

    /** The header column an amount cell sits under: most horizontal overlap, else closest right edge. */
    private fun columnOf(cell: PdfCell, columns: List<HeaderColumn>): Column? {
        if (columns.isEmpty() || cell.x1 <= cell.x0) return null
        val overlapping = columns.maxByOrNull { min(it.x1, cell.x1) - max(it.x0, cell.x0) }!!
        if (min(overlapping.x1, cell.x1) - max(overlapping.x0, cell.x0) > 0) return overlapping.column
        val nearest = columns.minByOrNull { abs(it.x1 - cell.x1) }!!
        val width = max(nearest.x1 - nearest.x0, cell.x1 - cell.x0)
        return nearest.column.takeIf { abs(nearest.x1 - cell.x1) <= width * 1.5f }
    }

    /** Source label for PDFs read from their text; scanned PDFs read with OCR use [SOURCE_OCR]. */
    const val SOURCE_TEXT = "PDF"
    const val SOURCE_OCR = "PDF (scanned, read with OCR)"

    /** Plain text rows (no positions): sign and amount come from the text only. */
    fun parse(
        rows: List<String>,
        fallbackCurrency: String,
        year: Int = LocalDate.now().year,
        source: String = SOURCE_TEXT,
        today: LocalDate = LocalDate.now(),
    ): StatementParseResult = parseRows(rows.map(PdfRow::plain), fallbackCurrency, year, source, today)

    /**
     * @param year used for dates without a year when the statement shows no full date at all
     */
    fun parseRows(
        rows: List<PdfRow>,
        fallbackCurrency: String,
        year: Int = LocalDate.now().year,
        source: String = SOURCE_TEXT,
        today: LocalDate = LocalDate.now(),
    ): StatementParseResult {
        val lines = mutableListOf<StatementLine>()
        var skipped = 0
        var lastDate: LocalDate? = null
        var lastDateRow = -10
        var previousText = ""
        var columns: List<HeaderColumn> = emptyList()
        val texts = rows.map { it.text }
        val currency = if (texts.any { EURO.containsMatchIn(it) }) "EUR" else fallbackCurrency
        val card = texts.any { CARD_STATEMENT.containsMatchIn(it) }
        // A statement that marks money out with "-" but never writes "+" shows money in unsigned.
        val allAmounts = texts.flatMap { AMOUNT.findAll(it).toList() }
        val unsignedMeansIn = allAmounts.any { isMinus(it) } && allAmounts.none { it.groupValues[1].trim() == "+" }
        // Dates without a year belong to the statement's period: take the year of its latest full date.
        val reference = texts.flatMap { DATE_IN_ROW.findAll(it).toList() }
            .mapNotNull { StatementValues.parseDate(it.value) }
            .filter { !it.isAfter(today.plusDays(1)) }
            .maxOrNull()
            ?: if (year == today.year) today else LocalDate.of(year, 12, 31)

        rows.forEachIndexed { i, pdfRow ->
            val row = pdfRow.text
            // Where each cell starts in [row], to find the cell (and so the column) of an amount.
            val cellStarts = pdfRow.cells.runningFold(0) { at, c -> at + c.text.trim().length + 3 }
            fun cellAt(offset: Int): PdfCell =
                pdfRow.cells[(cellStarts.indexOfLast { it <= offset }).coerceIn(0, pdfRow.cells.lastIndex)]

            // Dates are taken out first so "18.09" or "28.10.2026" is never read as an amount.
            val dateRanges = mutableListOf<IntRange>()
            var date: LocalDate? = null
            val short = LEADING_SHORT_DATES.find(row)
            if (short != null && AMOUNT.containsMatchIn(row.substring(short.range.last + 1))) {
                // Only a date when an amount follows; otherwise "18.09" may be an amount itself.
                date = shortDate(short.groupValues[1].toInt(), short.groupValues[2].toInt(), reference)
                if (date != null) dateRanges += short.range
            }
            if (date == null) {
                DATE_IN_ROW.find(row)?.let { m ->
                    date = StatementValues.parseDate(m.value, defaultYear = reference.year)
                    if (date != null) dateRanges += m.range
                }
            }
            if (date != null) {
                lastDate = date
                lastDateRow = i
            }
            val amounts = AMOUNT.findAll(row).filter { m -> dateRanges.none { it.first <= m.range.last && m.range.first <= it.last } }.toList()

            if (amounts.isEmpty()) {
                headerColumns(pdfRow)?.let {
                    columns = it
                    return@forEachIndexed
                }
            }
            if (amounts.isEmpty() || SKIP.containsMatchIn(row)) {
                // Text-only rows often hold the payee of the booking printed below them.
                if (amounts.isEmpty() && date == null) previousText = clean(row)
                return@forEachIndexed
            }
            if (card && CARD_BILL_PAYMENT.containsMatchIn(row)) return@forEachIndexed
            // Without a date of its own, a row of several amounts, or one under a "Total"/"Balance"
            // label, is a summary (e.g. "Total income  Total expenses" over "400.00  30.52").
            if (date == null && (amounts.size >= 3 || SKIP.containsMatchIn(previousText))) {
                previousText = ""
                return@forEachIndexed
            }

            // Position of the digits themselves: the match may start with the space before them.
            val placed = amounts.map { it to columnOf(cellAt(it.groups[2]!!.range.first), columns) }
            val debit = placed.firstOrNull { it.second == Column.DEBIT }?.first
            val credit = placed.firstOrNull { it.second == Column.CREDIT }?.first
            val inAmountColumn = placed.firstOrNull { it.second == Column.AMOUNT }?.first
            val unplaced = placed.filter { it.second == null }.map { it.first }
            val foreignColumn = placed.firstOrNull { it.second == Column.FOREIGN }?.first
            if (placed.isNotEmpty() && placed.all { it.second == Column.BALANCE || it.second == Column.FOREIGN }) {
                return@forEachIndexed     // only a running balance on this line
            }
            val byColumn = debit != null || credit != null || inAmountColumn != null

            val bookingDate = date ?: lastDate?.takeIf { byColumn || i - lastDateRow <= 2 }
            if (bookingDate == null) {
                skipped++
                return@forEachIndexed
            }

            // Bank statements: the first amount (a balance column may follow). Card statements:
            // the last one, in EUR; a foreign-currency amount may come before it.
            val amount = debit ?: credit ?: inAmountColumn
                ?: (if (card) unplaced.lastOrNull() else unplaced.firstOrNull())
                ?: return@forEachIndexed
            val value = StatementValues.parseAmount(amount.groupValues[2]) ?: return@forEachIndexed
            val minus = isMinus(amount)
            val plus = amount.groupValues[1].trim() == "+"
            val signed: Long
            val signGuessed: Boolean
            when {
                amount === debit -> { signed = -value; signGuessed = false }
                amount === credit -> { signed = value; signGuessed = false }
                card -> {
                    // A card charge is printed as a plain amount; refunds and credits carry "-" or "CR".
                    signed = if (minus || CREDIT_WORD.containsMatchIn(row)) value else -value
                    signGuessed = false
                }
                else -> {
                    signed = when {
                        minus -> -value
                        plus -> value
                        DEBIT_WORD.containsMatchIn(row) -> -value
                        CREDIT_WORD.containsMatchIn(row) -> value
                        unsignedMeansIn -> value
                        else -> -value     // no sign anywhere: most bookings are payments; the user can flip it
                    }
                    signGuessed = !minus && !plus && !unsignedMeansIn &&
                        !DEBIT_WORD.containsMatchIn(row) && !CREDIT_WORD.containsMatchIn(row)
                }
            }

            val foreignCode = FOREIGN_CODE.findAll(row).map { it.value }.firstOrNull { it != currency }
            val foreignAmount = foreignColumn
                ?: amounts.firstOrNull().takeIf { card && amounts.size >= 2 && it !== amount && foreignCode != null }
            val original = foreignAmount?.takeIf { foreignCode != null }?.let { StatementValues.parseAmount(it.groupValues[2]) }

            // The payee: what is left once dates, amounts and the foreign currency code are removed.
            val blanked = StringBuilder(row)
            (dateRanges + amounts.map { it.range }).forEach { r ->
                for (k in r) blanked.setCharAt(k, ' ')
            }
            var text = blanked.toString()
            if (original != null) text = text.replace(Regex("""\b$foreignCode\b"""), " ")
            val party = clean(text).ifBlank { previousText }
            if (value != 0L) {
                lines += StatementLine(
                    date = bookingDate,
                    amountCents = signed,
                    currency = currency,
                    party = party.take(80),
                    originalAmountCents = original,
                    originalCurrency = original?.let { foreignCode },
                    uncertain = signGuessed || (date == null && !byColumn),
                )
            }
            previousText = ""
        }
        return StatementParseResult(lines, currency.takeIf { it == "EUR" }, source, skipped)
    }

    /** "-7,42", "− 7,42", "7,42-" or "7,42 CR" (credit on card statements). */
    private fun isMinus(m: MatchResult) =
        m.groupValues[1].isNotBlank() && m.groupValues[1].trim() != "+" || m.groupValues[3].isNotBlank()

    /** 18.09 on a statement dated 08.10.26 is 18 Sep 2026; 28.12 on one dated 08.01.27 is 28 Dec 2026. */
    private fun shortDate(day: Int, month: Int, reference: LocalDate): LocalDate? {
        val d = runCatching { LocalDate.of(reference.year, month, day) }.getOrNull() ?: return null
        return if (d.isAfter(reference.plusDays(31))) d.minusYears(1) else d
    }

    private fun clean(s: String) = s.replace(Regex("""\s{2,}"""), " ").trim().trim('-', '|', ':', '·', '*', ' ')
}
