package com.billbook.app.importer

import com.billbook.app.export.CsvReader

/**
 * Reads bank statement CSV exports. Columns are recognised by their header names in English and
 * German (N26, Amex, Sparkasse, DKB, ING, Revolut, …); [Columns] can also be chosen by the user.
 */
object BankCsvParser {

    /** Column indexes; null when the statement has no such column. */
    data class Columns(
        val date: Int,
        val amount: Int? = null,
        val debit: Int? = null,
        val credit: Int? = null,
        val party: Int? = null,
        val reference: Int? = null,
        val kind: Int? = null,
        val currency: Int? = null,
        val originalAmount: Int? = null,
        val originalCurrency: Int? = null,
    )

    // Best match first. Header cells are compared lower-cased.
    private val DATE = listOf("booking date", "buchungstag", "buchungsdatum", "transaction date", "date", "datum",
        "posted date", "posting date", "value date", "valuta", "wertstellung")
    private val FOREIGN = Regex("original|foreign|fremd")
    private val AMOUNT = Regex("""^(amount|betrag|umsatz)\b|\bamount\b|\bbetrag\b""")
    private val DEBIT = Regex("""^(debit|soll|withdrawals?|paid out|money out|ausgang|belastung)\b""")
    private val CREDIT = Regex("""^(credit|haben|deposits?|paid in|money in|eingang|gutschrift)\b""")
    private val PARTY = listOf("partner name", "payee", "beneficiary", "counterparty", "merchant",
        "zahlungsempfänger", "empfänger", "auftraggeber / empfänger", "auftraggeber/empfänger",
        "beguenstigter/zahlungspflichtiger", "begünstigter/zahlungspflichtiger", "auftraggeber", "begünstigter",
        "beguenstigter", "name", "description", "beschreibung")
    private val REFERENCE = listOf("payment reference", "verwendungszweck", "reference", "details", "memo",
        "buchungstext", "description", "beschreibung")
    private val KIND = listOf("transaction type", "type", "umsatzart", "buchungsart", "buchungstext")
    private val CURRENCY = Regex("""^(currency|währung|waehrung)$""")
    private val ORIGINAL_AMOUNT = Regex("""original amount|amount \(foreign currency\)|foreign amount|fremdwährungsbetrag|betrag fremdwährung""")
    private val ORIGINAL_CURRENCY = Regex("""original currency|type foreign currency|foreign currency$|fremdwährung$""")
    private val CURRENCY_IN_HEADER = Regex("""\(([a-z]{3})\)""")

    fun parse(text: String, fallbackCurrency: String, override: Columns? = null): StatementParseResult {
        val rows = CsvReader.parse(text, CsvReader.detectDelimiter(text)).map { row -> row.map { it.trim() } }
        val headerIndex = rows.take(30).indexOfFirst { recognise(it) != null }
        if (override == null && headerIndex < 0) {
            val header = rows.firstOrNull { r -> r.count { it.isNotBlank() } >= 2 }.orEmpty()
            return StatementParseResult(emptyList(), null, "CSV", unrecognisedHeader = header)
        }
        val start = if (headerIndex >= 0) headerIndex else 0
        val header = rows.getOrNull(start).orEmpty().map { it.lowercase() }
        val columns = override ?: recognise(rows[start])!!

        val accountCurrency = columns.amount?.let { header.getOrNull(it) }
            ?.let { CURRENCY_IN_HEADER.find(it)?.groupValues?.get(1)?.uppercase() }
        val data = rows.drop(start + 1).filter { r -> r.any { it.isNotBlank() } }
        val dayFirst = !StatementValues.looksMonthFirst(data.mapNotNull { it.getOrNull(columns.date) })

        var skipped = 0
        val lines = data.mapNotNull { r ->
            fun cell(i: Int?) = i?.let { r.getOrNull(it) }.orEmpty()
            val date = StatementValues.parseDate(cell(columns.date), dayFirst)
            val amount = StatementValues.parseAmount(cell(columns.amount))
                ?: debitCredit(cell(columns.debit), cell(columns.credit))
            if (date == null || amount == null || amount == 0L) {
                if (cell(columns.date).isNotBlank()) skipped++
                return@mapNotNull null
            }
            val currency = cell(columns.currency).uppercase().takeIf { it.length == 3 }
                ?: accountCurrency ?: fallbackCurrency
            val originalCurrency = cell(columns.originalCurrency).uppercase().takeIf { it.length == 3 && it != currency }
            val originalAmount = originalCurrency?.let { StatementValues.parseAmount(cell(columns.originalAmount)) }
                ?.let { kotlin.math.abs(it) }?.takeIf { it > 0 }
            val reference = cell(columns.reference).takeIf { columns.reference != columns.party }.orEmpty()
            StatementLine(
                date = date,
                amountCents = amount,
                currency = currency,
                party = cell(columns.party).ifBlank { reference }.take(80),
                reference = reference,
                kind = cell(columns.kind),
                originalAmountCents = originalAmount,
                originalCurrency = originalAmount?.let { originalCurrency },
            )
        }
        val isN26 = header.any { it.startsWith("amount (eur)") } && header.any { it == "partner name" || it == "payee" }
        return StatementParseResult(lines, accountCurrency, if (isN26) "N26 CSV" else "CSV", skipped)
    }

    /** Separate money-out / money-in columns: credit minus debit. */
    private fun debitCredit(debit: String, credit: String): Long? {
        val out = StatementValues.parseAmount(debit)?.let { kotlin.math.abs(it) } ?: 0L
        val inn = StatementValues.parseAmount(credit)?.let { kotlin.math.abs(it) } ?: 0L
        return if (debit.isBlank() && credit.isBlank()) null else inn - out
    }

    /** Recognises the header row; null when it has no date column or no amount column(s). */
    internal fun recognise(headerRow: List<String>): Columns? {
        val h = headerRow.map { it.trim().lowercase() }
        fun firstOf(names: List<String>, exclude: Set<Int> = emptySet()): Int? {
            for (name in names) {
                h.indices.firstOrNull { it !in exclude && h[it] == name }?.let { return it }
            }
            for (name in names) {
                h.indices.firstOrNull { it !in exclude && h[it].startsWith(name) }?.let { return it }
            }
            return null
        }
        fun firstMatch(regex: Regex, exclude: Set<Int> = emptySet()) =
            h.indices.firstOrNull { it !in exclude && regex.containsMatchIn(h[it]) }

        val date = firstOf(DATE) ?: return null
        val amount = h.indices.firstOrNull { AMOUNT.containsMatchIn(h[it]) && !FOREIGN.containsMatchIn(h[it]) }
        val debit = firstMatch(DEBIT)
        val credit = firstMatch(CREDIT)
        if (amount == null && (debit == null || credit == null)) return null
        val party = firstOf(PARTY, setOf(date))
        val reference = firstOf(REFERENCE, setOfNotNull(date, party))
        return Columns(
            date = date,
            amount = amount,
            debit = debit.takeIf { amount == null },
            credit = credit.takeIf { amount == null },
            party = party,
            reference = reference,
            kind = firstOf(KIND, setOfNotNull(date, party, reference)),
            currency = firstMatch(CURRENCY),
            originalAmount = firstMatch(ORIGINAL_AMOUNT),
            originalCurrency = firstMatch(ORIGINAL_CURRENCY),
        )
    }
}
