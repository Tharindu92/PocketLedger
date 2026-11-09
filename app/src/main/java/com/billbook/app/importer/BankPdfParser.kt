package com.billbook.app.importer

import com.billbook.app.ocr.ReceiptParser
import java.time.LocalDate

/**
 * Finds bookings in the text rows of a PDF statement. Statement layouts differ a lot, so this is a
 * best effort: a booking is a row with an amount, dated by a date on the same row or just above it.
 * Lines whose sign or date had to be guessed are marked [StatementLine.uncertain].
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

    /** Source label for PDFs read from their text; scanned PDFs read with OCR use [SOURCE_OCR]. */
    const val SOURCE_TEXT = "PDF"
    const val SOURCE_OCR = "PDF (scanned, read with OCR)"

    /**
     * @param year used for dates without a year when the statement shows no full date at all
     */
    fun parse(
        rows: List<String>,
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
        val currency = if (rows.any { EURO.containsMatchIn(it) }) "EUR" else fallbackCurrency
        val card = rows.any { CARD_STATEMENT.containsMatchIn(it) }
        // Dates without a year belong to the statement's period: take the year of its latest full date.
        val reference = rows.flatMap { DATE_IN_ROW.findAll(it).toList() }
            .mapNotNull { StatementValues.parseDate(it.value) }
            .filter { !it.isAfter(today.plusDays(1)) }
            .maxOrNull()
            ?: if (year == today.year) today else LocalDate.of(year, 12, 31)

        rows.forEachIndexed { i, row ->
            var work = row
            var date: LocalDate? = null
            val short = LEADING_SHORT_DATES.find(row)
            if (short != null) {
                val rest = row.substring(short.range.last + 1)
                // Only a date when an amount follows; otherwise "18.09" may be an amount itself.
                if (AMOUNT.containsMatchIn(rest)) {
                    date = shortDate(short.groupValues[1].toInt(), short.groupValues[2].toInt(), reference)
                    if (date != null) work = rest
                }
            }
            if (date == null) {
                DATE_IN_ROW.find(row)?.let { m ->
                    date = StatementValues.parseDate(m.value, defaultYear = reference.year)
                    if (date != null) work = work.replace(m.value, " ")
                }
            }
            if (date != null) {
                lastDate = date
                lastDateRow = i
            }
            val amounts = AMOUNT.findAll(work).toList()
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
            val bookingDate = date ?: lastDate?.takeIf { i - lastDateRow <= 2 }
            if (bookingDate == null) {
                skipped++
                return@forEachIndexed
            }

            // Bank statements: the first amount (a balance column may follow). Card statements:
            // the last one, in EUR; a foreign-currency amount may come before it.
            val amount = if (card) amounts.last() else amounts.first()
            val value = StatementValues.parseAmount(amount.groupValues[2]) ?: return@forEachIndexed
            val minus = amount.groupValues[1].isNotBlank() && amount.groupValues[1].trim() != "+" ||
                amount.groupValues[3].isNotBlank()
            val plus = amount.groupValues[1].trim() == "+"
            val signed: Long
            val signGuessed: Boolean
            if (card) {
                // A card charge is printed as a plain amount; refunds and credits carry "-" or "CR".
                signed = if (minus || CREDIT_WORD.containsMatchIn(row)) value else -value
                signGuessed = false
            } else {
                signed = when {
                    minus -> -value
                    plus -> value
                    DEBIT_WORD.containsMatchIn(row) -> -value
                    CREDIT_WORD.containsMatchIn(row) -> value
                    else -> -value     // no sign printed: most bookings are payments; the user can flip it
                }
                signGuessed = !minus && !plus && !DEBIT_WORD.containsMatchIn(row) && !CREDIT_WORD.containsMatchIn(row)
            }
            val foreignCode = FOREIGN_CODE.findAll(work).map { it.value }.firstOrNull { it != currency }
            val original = if (card && amounts.size >= 2 && foreignCode != null) {
                StatementValues.parseAmount(amounts.first().groupValues[2])
            } else {
                null
            }

            var text = work
            amounts.forEach { text = text.replace(it.value, " ") }
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
                    uncertain = signGuessed || date == null,
                )
            }
            previousText = ""
        }
        return StatementParseResult(lines, currency.takeIf { it == "EUR" }, source, skipped)
    }

    /** 18.09 on a statement dated 08.10.26 is 18 Sep 2026; 28.12 on one dated 08.01.27 is 28 Dec 2026. */
    private fun shortDate(day: Int, month: Int, reference: LocalDate): LocalDate? {
        val d = runCatching { LocalDate.of(reference.year, month, day) }.getOrNull() ?: return null
        return if (d.isAfter(reference.plusDays(31))) d.minusYears(1) else d
    }

    private fun clean(s: String) = s.replace(Regex("""\s{2,}"""), " ").trim().trim('-', '|', ':', '·', '*', ' ')
}
