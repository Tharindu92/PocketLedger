package com.billbook.app.importer

import java.time.LocalDate

/**
 * One booking read from a bank statement. [amountCents] is signed from the account's point of
 * view: positive = money in, negative = money out, in [currency] (the account currency).
 */
data class StatementLine(
    val date: LocalDate,
    val amountCents: Long,
    val currency: String,
    val party: String,
    val reference: String = "",
    /** The bank's transaction type, e.g. "MasterCard Payment" or "Outgoing Transfer". */
    val kind: String = "",
    /** Card payments abroad: the amount and currency of the purchase itself (positive). */
    val originalAmountCents: Long? = null,
    val originalCurrency: String? = null,
    /** Read with text recognition and the sign or date had to be guessed: needs a closer look. */
    val uncertain: Boolean = false,
)

data class StatementParseResult(
    val lines: List<StatementLine>,
    /** Account currency, when the statement says so. */
    val currency: String?,
    /** "N26 CSV", "CSV" or "PDF", shown to the user. */
    val source: String,
    /** Rows that looked like bookings but could not be read. */
    val skipped: Int = 0,
    /** For CSV files whose columns were not recognised: the header cells to choose from. */
    val unrecognisedHeader: List<String>? = null,
)

/** Dates and amounts as banks print them: "-1.234,56 €", "1,234.56-", "(7.42)", "28.10.2026", "2026-10-28". */
object StatementValues {

    private val MINUS = Regex("""^\s*[-−–]|[-−–]\s*$|^\s*\(.*\)\s*$""")

    /** Signed amount in cents, or null when [raw] holds no number. */
    fun parseAmount(raw: String): Long? {
        val trimmed = raw.trim()
        val negative = MINUS.containsMatchIn(trimmed.replace(Regex("""[€$£A-Za-z\s]"""), ""))
        val s = trimmed.filter { it.isDigit() || it == '.' || it == ',' }
        if (s.none { it.isDigit() }) return null
        val lastSep = s.indexOfLast { it == '.' || it == ',' }
        val decimals = if (lastSep >= 0) s.length - lastSep - 1 else 0
        val (whole, fraction) = if (lastSep >= 0 && decimals in 1..2) {
            s.substring(0, lastSep) to s.substring(lastSep + 1)
        } else {
            s to ""
        }
        val w = whole.replace(".", "").replace(",", "").ifEmpty { "0" }.toLongOrNull() ?: return null
        val cents = w * 100 + (fraction.padEnd(2, '0').toLongOrNull() ?: 0L)
        return if (negative) -cents else cents
    }

    private val ISO = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val NUMERIC = Regex("""(?<!\d)(\d{1,2})[./-](\d{1,2})[./-](\d{4}|\d{2})(?!\d)""")
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "mär" to 3, "mrz" to 3, "apr" to 4, "may" to 5, "mai" to 5,
        "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "okt" to 10, "nov" to 11, "dec" to 12, "dez" to 12,
    )
    private val TEXT_MONTH = Regex(
        """(?<!\d)(\d{1,2})\.?\s*(jan|feb|mär|mar|mrz|apr|mai|may|jun|jul|aug|sep|okt|oct|nov|dez|dec)[a-zä]*\.?\s*(\d{4}|\d{2})?(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param dayFirst read 03/04/2026 as 3 April (most of the world) rather than 4 March (US)
     * @param defaultYear for dates printed without a year, e.g. "28 Okt"
     */
    fun parseDate(raw: String, dayFirst: Boolean = true, defaultYear: Int? = null): LocalDate? {
        ISO.find(raw)?.let { m -> date(m.int(1), m.int(2), m.int(3))?.let { return it } }
        NUMERIC.find(raw)?.let { m ->
            val a = m.int(1)
            val b = m.int(2)
            val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            (if (dayFirst) date(y, b, a) ?: date(y, a, b) else date(y, a, b) ?: date(y, b, a))?.let { return it }
        }
        TEXT_MONTH.find(raw)?.let { m ->
            val month = MONTHS[m.groupValues[2].lowercase().take(3)] ?: return@let
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
                ?: defaultYear ?: return@let
            date(year, month, m.int(1))?.let { return it }
        }
        return null
    }

    /** True when some value reads as month/day (US): its second number is over 12. */
    fun looksMonthFirst(values: List<String>): Boolean {
        var monthFirst = false
        for (v in values) {
            val m = NUMERIC.find(v) ?: continue
            if (m.int(1) > 12) return false
            if (m.int(2) > 12) monthFirst = true
        }
        return monthFirst
    }

    private fun MatchResult.int(i: Int) = groupValues[i].toInt()
    private fun date(y: Int, m: Int, d: Int) = runCatching { LocalDate.of(y, m, d) }.getOrNull()
}
