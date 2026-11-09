package com.billbook.app.importer

import java.time.LocalDate

/**
 * Finds bookings in the text rows of a PDF statement (read with OCR). Statement layouts differ a
 * lot, so this is a best effort: a booking is a row with an amount, dated by a date on the same
 * row or just above it. Lines whose sign or date had to be guessed are marked [StatementLine.uncertain].
 */
object BankPdfParser {

    // An amount with exactly two decimals, optional sign/currency; not part of a date ("12.05.2024").
    private val AMOUNT = Regex(
        """(?<![\d.,])([-+−]\s?)?(?:€|EUR|\$|£)?\s?(\d{1,3}(?:[.,\s]\d{3})*[.,]\d{2})(?![.,]?\d)(\s?[-−])?(\s?(?:€|EUR))?"""
    )
    private val DATE_IN_ROW = Regex(
        """(?<!\d)(\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[./]\d{1,2}[./](?:\d{4}|\d{2})|\d{1,2}\.?\s*(?:jan|feb|mär|mar|mrz|apr|mai|may|jun|jul|aug|sep|okt|oct|nov|dez|dec)[a-zä]*\.?(?:\s*\d{4})?)(?!\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val SKIP = Regex(
        """\b(balance|saldo|kontostand|opening|closing|übertrag|carried forward|brought forward|total|summe|""" +
            """zwischensumme|page|seite|iban|bic|statement|kontoauszug|account number|kontonummer)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DEBIT_WORD = Regex("""\b(S|Soll|Dr|Debit|Lastschrift|Belastung|Kartenzahlung|Card payment)\b""")
    private val CREDIT_WORD = Regex("""\b(H|Haben|Cr|Credit|Gutschrift|Incoming|Eingang)\b""")
    private val EURO = Regex("""€|\bEUR\b""")

    /** Source label for PDFs read from their text; scanned PDFs read with OCR use [SOURCE_OCR]. */
    const val SOURCE_TEXT = "PDF"
    const val SOURCE_OCR = "PDF (scanned, read with OCR)"

    fun parse(
        rows: List<String>,
        fallbackCurrency: String,
        year: Int = LocalDate.now().year,
        source: String = SOURCE_TEXT,
    ): StatementParseResult {
        val lines = mutableListOf<StatementLine>()
        var skipped = 0
        var lastDate: LocalDate? = null
        var lastDateRow = -10
        var previousText = ""
        val currency = if (rows.any { EURO.containsMatchIn(it) }) "EUR" else fallbackCurrency

        rows.forEachIndexed { i, row ->
            val dateMatch = DATE_IN_ROW.find(row)
            val date = dateMatch?.let { StatementValues.parseDate(it.value, defaultYear = year) }
            if (date != null) {
                lastDate = date
                lastDateRow = i
            }
            val amount = AMOUNT.find(row)
            if (amount == null || SKIP.containsMatchIn(row)) {
                // Text-only rows often hold the payee of the booking printed below them.
                if (amount == null && date == null) previousText = clean(row)
                return@forEachIndexed
            }
            // Without a date of its own, a row of several amounts, or one under a "Total"/"Balance"
            // label, is a summary (e.g. "Total income  Total expenses" over "400.00  30.52").
            if (date == null && (AMOUNT.findAll(row).count() >= 3 || SKIP.containsMatchIn(previousText))) {
                previousText = ""
                return@forEachIndexed
            }
            val bookingDate = date ?: lastDate?.takeIf { i - lastDateRow <= 2 }
            if (bookingDate == null) {
                skipped++
                return@forEachIndexed
            }
            val value = StatementValues.parseAmount(amount.groupValues[2]) ?: return@forEachIndexed
            val explicitMinus = amount.groupValues[1].isNotBlank() && amount.groupValues[1].trim() != "+" ||
                amount.groupValues[3].isNotBlank()
            val explicitPlus = amount.groupValues[1].trim() == "+"
            val signed = when {
                explicitMinus -> -value
                explicitPlus -> value
                DEBIT_WORD.containsMatchIn(row) -> -value
                CREDIT_WORD.containsMatchIn(row) -> value
                else -> -value     // no sign printed: most bookings are payments; the user can flip it
            }
            val signGuessed = !explicitMinus && !explicitPlus &&
                !DEBIT_WORD.containsMatchIn(row) && !CREDIT_WORD.containsMatchIn(row)

            var text = row
            dateMatch?.let { text = text.replace(it.value, " ") }
            AMOUNT.findAll(row).forEach { text = text.replace(it.value, " ") }
            val party = clean(text).ifBlank { previousText }
            if (value != 0L) {
                lines += StatementLine(
                    date = bookingDate,
                    amountCents = signed,
                    currency = currency,
                    party = party.take(80),
                    uncertain = signGuessed || date == null,
                )
            }
            previousText = ""
        }
        return StatementParseResult(lines, currency.takeIf { it == "EUR" }, source, skipped)
    }

    private fun clean(s: String) = s.replace(Regex("""\s{2,}"""), " ").trim().trim('-', '|', ':', '·', '*', ' ')
}
