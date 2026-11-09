package com.billbook.app.ocr

import java.time.LocalDate
import kotlin.math.abs

/** One line of recognised text with its bounding box on the photo. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** Fields extracted from a bill. Anything not found is null. */
data class ParsedReceipt(
    val totalCents: Long? = null,
    val taxCents: Long? = null,
    val billNumber: String? = null,
    val merchant: String? = null,
    val date: LocalDate? = null,
    val paymentMethod: String? = null,
    val category: String? = null,
    /** ISO 4217 code such as "LKR" or "USD". */
    val currency: String? = null,
    // Every plausible value found, best first (the first one is the field above). Shown as
    // one-tap alternatives because heuristics cannot always tell which one is right.
    val totalOptions: List<Long> = emptyList(),
    val billNumberOptions: List<String> = emptyList(),
    val merchantOptions: List<String> = emptyList(),
    val dateOptions: List<LocalDate> = emptyList(),
)

/**
 * Heuristic extraction of accounting details from OCR text. Pure Kotlin (no Android
 * dependencies) so it can be unit-tested.
 *
 * Bills usually print labels on the left and values on the right; OCR often returns
 * those as separate lines, so lines are first regrouped into visual rows by their
 * vertical position.
 */
object ReceiptParser {

    /**
     * @param homeCurrency the user's default currency, used to resolve ambiguous symbols
     *   ("Rs" could be LKR/INR/PKR, "$" could be USD/AUD/SGD…).
     */
    fun parse(
        lines: List<OcrLine>,
        today: LocalDate = LocalDate.now(),
        homeCurrency: String = "LKR",
    ): ParsedReceipt {
        val rowLines = groupLines(lines)
        val rows = rowLines.map(::joinRow)
        val totals = totalCandidates(rows)
        val billNumbers = billNumberCandidates(rowLines)
        val merchants = merchantCandidates(rowLines)
        val dates = dateCandidates(rows, today)
        return ParsedReceipt(
            totalCents = totals.firstOrNull(),
            taxCents = findTax(rows, totals.firstOrNull()),
            billNumber = billNumbers.firstOrNull(),
            merchant = merchants.firstOrNull(),
            date = dates.firstOrNull(),
            paymentMethod = findPaymentMethod(rows),
            category = guessCategory(rows),
            currency = detectCurrency(rows, homeCurrency),
            totalOptions = totals.take(MAX_OPTIONS),
            billNumberOptions = billNumbers.take(MAX_OPTIONS),
            merchantOptions = merchants.take(MAX_OPTIONS),
            dateOptions = dates.take(MAX_OPTIONS),
        )
    }

    private const val MAX_OPTIONS = 4

    /** Convenience for plain text where every line is already its own row. */
    fun parseText(text: String, today: LocalDate = LocalDate.now(), homeCurrency: String = "LKR"): ParsedReceipt =
        parse(
            text.lines().filter { it.isNotBlank() }
                .mapIndexed { i, t -> OcrLine(t, 0, i * 20, 100, i * 20 + 15) },
            today,
            homeCurrency,
        )

    // ------------------------------------------------------------- currency

    internal val ISO_CODES = listOf(
        "LKR", "SLR", "USD", "EUR", "GBP", "INR", "AUD", "CAD", "SGD", "NZD", "AED", "SAR", "QAR",
        "KWD", "OMR", "BHD", "JPY", "CNY", "RMB", "HKD", "MYR", "THB", "PKR", "NPR", "BDT", "MVR", "CHF",
    )
    // Case-sensitive: receipts print codes in capitals, and this avoids matching ordinary words.
    private val ISO_CURRENCY = Regex("""\b(${ISO_CODES.joinToString("|")})\b""")
    private val CURRENCY_ALIASES = mapOf("SLR" to "LKR", "RMB" to "CNY")
    private val RUPEE_CURRENCIES = setOf("LKR", "INR", "PKR", "NPR")
    private val DOLLAR_CURRENCIES = setOf("USD", "AUD", "CAD", "SGD", "NZD", "HKD")
    private val RUPEE = Regex("""(?i)\brs\b\.?|\brupees?\b""")
    private val SYMBOL_RULES: List<Pair<Regex, String>> = listOf(
        """₹""" to "INR",
        """€""" to "EUR",
        """£""" to "GBP",
        """฿""" to "THB",
        """US\$""" to "USD",
        """NZ\$""" to "NZD",
        """HK\$""" to "HKD",
        """S\$""" to "SGD",
        """A\$""" to "AUD",
        """C\$""" to "CAD",
        """(?i)\bdhs?\b|د\.إ""" to "AED",
        """\bRM\s?\d""" to "MYR",
        """\bMRf\b|\bRf\.?\s?\d""" to "MVR",
    ).map { (pattern, code) -> Regex(pattern) to code }

    internal fun detectCurrency(rows: List<String>, homeCurrency: String): String? {
        val text = rows.joinToString("\n")
        val home = homeCurrency.uppercase()

        // 1. Explicit ISO codes win; take the most frequent one.
        val codes = ISO_CURRENCY.findAll(text)
            .map { m -> m.groupValues[1].let { CURRENCY_ALIASES[it] ?: it } }
            .toList()
        if (codes.isNotEmpty()) return codes.groupingBy { it }.eachCount().maxBy { it.value }.key

        // 2. Unambiguous symbols / prefixes.
        SYMBOL_RULES.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.let { return it.second }

        // 3. Ambiguous symbols, resolved with the user's home currency.
        if (text.contains('¥') || text.contains('￥')) return if (home == "CNY") "CNY" else "JPY"
        if (text.contains('$')) return if (home in DOLLAR_CURRENCIES) home else "USD"
        if (RUPEE.containsMatchIn(text)) return if (home in RUPEE_CURRENCIES) home else "LKR"
        return null
    }

    // ---------------------------------------------------------------- rows

    internal fun groupIntoRows(lines: List<OcrLine>): List<String> = groupLines(lines).map(::joinRow)

    private fun joinRow(row: List<OcrLine>): String = row.joinToString("  ") { it.text.trim() }

    /** Visual rows, top to bottom; the lines inside each row are sorted left to right. */
    internal fun groupLines(lines: List<OcrLine>): List<List<OcrLine>> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { it.centerY }.average()
                val tolerance = minOf(row.first().height, line.height) * 0.5
                if (abs(line.centerY - rowCenter) <= tolerance) {
                    row.add(line)
                    continue
                }
            }
            rows.add(mutableListOf(line))
        }
        return rows.map { row -> row.sortedBy { it.left } }
    }

    // ------------------------------------------------------------- amounts

    // 1,234.56 | 1234.56 | 1234,56 | 1.234,56 (German) | 2.500.00 (OCR misreading a comma as a dot)
    // — but not dates (12.05.24), percentages (18.00%) or long ids.
    private val DECIMAL_AMOUNT =
        Regex("""(?<!\d)(?<!\d[.,])(\d{1,3}(?:[.,]\d{3})+|\d{1,9})[.,](\d{2})(?!\d)(?![.,]\d)(?!\s*%)""")
    private val INTEGER_AMOUNT = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d{1,9})(?![\d.,])(?!\s*%)""")
    private val CURRENCY_TOKENS = Regex("""(?i)\b(rs|lkr|slr|usd|inr|eur|gbp|aud|sgd|cad|nzd|aed|myr|rm|pkr)\b|[$€£₹¥฿]""")

    internal fun amountsIn(row: String): List<Long> =
        DECIMAL_AMOUNT.findAll(row).mapNotNull { m ->
            val whole = m.groupValues[1].replace(",", "").replace(".", "").toLongOrNull() ?: return@mapNotNull null
            whole * 100 + m.groupValues[2].toLong()
        }.toList()

    private fun integerAmountsIn(row: String): List<Long> =
        INTEGER_AMOUNT.findAll(row).mapNotNull { m ->
            m.groupValues[1].replace(",", "").toLongOrNull()?.times(100)
        }.toList()

    // Ordered from most to least specific.
    private val TOTAL_KEYS = listOf(
        "grand total", "total amount", "total payable", "amount payable", "net payable",
        "amount due", "balance due", "total due", "net total", "net amount", "total",
    )
    private val TOTAL_EXCLUDE = listOf(
        "sub total", "subtotal", "sub-total", "total qty", "total quantity", "total item",
        "no of item", "no. of item", "total discount", "total saving", "total tax", "total vat", "total gst",
    )
    private val FALLBACK_EXCLUDE = listOf("cash", "change", "tender", "paid", "balance", "tel", "phone")

    internal fun findTotal(rows: List<String>): Long? = totalCandidates(rows).firstOrNull()

    /** Labelled totals first (most specific label, then the larger amount), then other large amounts. */
    internal fun totalCandidates(rows: List<String>): List<Long> {
        data class Candidate(val rank: Int, val amount: Long)
        val labelled = mutableListOf<Candidate>()
        rows.forEachIndexed { i, row ->
            val lower = row.lowercase()
            if (TOTAL_EXCLUDE.any { lower.contains(it) }) return@forEachIndexed
            val rank = TOTAL_KEYS.indexOfFirst { lower.contains(it) }
            if (rank < 0) return@forEachIndexed

            val amount = amountsIn(row).lastOrNull()
                ?: integerAmountsIn(row).lastOrNull()
                ?: rows.getOrNull(i + 1)
                    // Value printed alone on the next line (e.g. "TOTAL" / "Rs. 1,450.00").
                    ?.takeIf { next -> CURRENCY_TOKENS.replace(next, "").none { it.isLetter() } }
                    ?.let { amountsIn(it).lastOrNull() }

            if (amount != null && amount > 0) labelled += Candidate(rank, amount)
        }
        // Without a recognised "total" label, the largest amount (ignoring cash/change lines) is
        // the best guess; with one, the other large amounts are still offered as alternatives.
        val others = rows
            .filterNot { row -> row.lowercase().let { l -> FALLBACK_EXCLUDE.any { l.contains(it) } } }
            .flatMap { amountsIn(it) }
            .filter { it > 0 }
            .sortedDescending()
        return (labelled.sortedWith(compareBy({ it.rank }, { -it.amount })).map { it.amount } + others).distinct()
    }

    // ----------------------------------------------------------------- tax

    private val TAX_WORD = Regex("""\b(vat|gst|tax|sscl|nbt)\b""", RegexOption.IGNORE_CASE)
    private val TAX_EXCLUDE = Regex(
        """\b(invoice|reg|registration|no|number|tin|id|incl|including|inclusive|exempt)\b""",
        RegexOption.IGNORE_CASE,
    )

    internal fun findTax(rows: List<String>, total: Long?): Long? =
        rows.filter { TAX_WORD.containsMatchIn(it) && !TAX_EXCLUDE.containsMatchIn(it) }
            .mapNotNull { amountsIn(it).lastOrNull() }
            .filter { it > 0 && (total == null || it < total) }
            .maxOrNull()

    // ---------------------------------------------------------- bill number

    private const val BILL_WORDS =
        "invoice|inv|bill|receipt|rcpt|ref|order|txn|trans|transaction|voucher|doc|app|application"
    private const val NUMBER_WORDS = "no|num|number|nbr|id|#"
    private val BILL_NO = Regex(
        """\b($BILL_WORDS)\b\.?\s*(?:$NUMBER_WORDS)?\.?\s*[:#\-]?\s*#?\s*([A-Za-z0-9][A-Za-z0-9\-/]{2,24})""",
        RegexOption.IGNORE_CASE,
    )
    /** A label with its value printed elsewhere, e.g. "Receipt No. :" with the number on the next row. */
    private val BILL_LABEL_ONLY = Regex(
        """\b($BILL_WORDS)\b\.?\s*(?:$NUMBER_WORDS)\.?\s*[:#\-]?\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val VALUE_TOKEN = Regex("""[A-Za-z0-9][A-Za-z0-9\-/]{2,24}""")
    private val LOOKS_LIKE_DATE = Regex("""^\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}$""")
    private val PUNCTUATION_ONLY = Regex("""^[\s:#\-.]*$""")

    /** Lower is better: a receipt/invoice number beats an order number, which beats a reference. */
    private fun billRank(word: String) = when (word.lowercase()) {
        "invoice", "inv", "bill", "receipt", "rcpt" -> 0
        "ref" -> 2
        "app", "application" -> 3
        else -> 1
    }

    /** At least three digits, so OCR fragments of words ("0des") are not taken for numbers. */
    private fun isBillValue(value: String) =
        value.count { it.isDigit() } >= 3 && !LOOKS_LIKE_DATE.matches(value) && amountsIn(value).isEmpty()

    internal fun findBillNumber(rows: List<List<OcrLine>>): String? = billNumberCandidates(rows).firstOrNull()

    internal fun billNumberCandidates(rows: List<List<OcrLine>>): List<String> {
        data class Candidate(val rank: Int, val row: Int, val value: String)
        val candidates = mutableListOf<Candidate>()

        rows.forEachIndexed { i, row ->
            // 1. Value on the same row: "Bill No: 004512".
            for (m in BILL_NO.findAll(joinRow(row))) {
                val value = m.groupValues[2].trimEnd('-', '/')
                if (isBillValue(value)) candidates += Candidate(billRank(m.groupValues[1]), i, value)
            }
            // 2. Value printed under the label, as in two-column forms:
            //    "App. No. :        Receipt No. :"
            //    "DRPENIA1B23C45    12600000123"
            //    Stray specks from the photo background can land on a line of their own in
            //    between, so up to three lines below are tried.
            val labels = row.filterNot { PUNCTUATION_ONLY.matches(it.text) }
            labels.forEachIndexed { column, label ->
                val m = BILL_LABEL_ONLY.find(label.text) ?: return@forEachIndexed
                for (next in rows.subList(i + 1, minOf(rows.size, i + 4))) {
                    val values = next.filterNot { PUNCTUATION_ONLY.matches(it.text) }
                    // Same column when both rows have the same number of cells, otherwise the
                    // value that starts closest to where the label ends.
                    val cell = (if (labels.size == values.size) values[column]
                    else values.minByOrNull { abs(it.left - label.right) }) ?: continue
                    val value = VALUE_TOKEN.findAll(cell.text).map { it.value.trimEnd('-', '/') }
                        .firstOrNull(::isBillValue) ?: continue
                    candidates += Candidate(billRank(m.groupValues[1]), i, value)
                    break
                }
            }
        }
        return candidates.sortedWith(compareBy({ it.rank }, { it.row })).map { it.value }.distinct()
    }

    // ------------------------------------------------------------- merchant

    private val MERCHANT_SKIP = Regex(
        """\b(receipt|invoice|tax|vat|gst|tel|phone|fax|mobile|hotline|date|time|cashier|counter|""" +
            """terminal|pos|thank|thanks|bill|order|table|www|http|email|no|total|subtotal|sum|summe|""" +
            """amount|balance|cash|change|qty|description|item|items|quittung|beleg|kassenbon|""" +
            """steuer|mwst|ust|kasse|tisch|kellner)\b|rechnung\b|@|\.com|\.lk""",
        RegexOption.IGNORE_CASE,
    )
    private val WELCOME = Regex("""^\s*welcome\s+to\s+""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s{2,}""")
    /** Document titles printed above or beside the business name. */
    private val TITLE_PREFIX = Regex(
        """^\s*(?:tax\s+invoice|cash\s+receipt|official\s+receipt|sales\s+receipt|receipt|invoice|bill)\b[\s:.\-]*""",
        RegexOption.IGNORE_CASE,
    )
    private val ORGANISATION_WORDS = Regex(
        """\b(department|dept|ministry|office|authority|board|council|corporation|commission|bureau|agency|""" +
            """registry|registrar|municipal|government|secretariat|company|ltd|limited|pvt|plc|inc|llc|gmbh|""" +
            """ag|kg|ohg|ug|bank|hospital|university|institute|college|school|society|association|club|trust|""" +
            """foundation|stores?|shop|mart|market|supermarket|traders|trading|enterprises?|holdings|group)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val WORD = Regex("""\p{L}+""")

    /**
     * Picks the business name from the top of the bill. Each candidate line gets a score instead of
     * simply taking the first one, so stray text in the photo (a newspaper under the receipt, a torn
     * header) loses to a line that looks like a business name.
     *
     * Each OCR line is judged on its own rather than the whole visual row, so a speck of background
     * text that happens to sit level with the name ("devel  DEPARTMENT FOR …") is not glued onto it.
     */
    internal fun findMerchant(rows: List<List<OcrLine>>): String? = merchantCandidates(rows).firstOrNull()

    /**
     * Business-name candidates scoring 0 or more, best first (earlier line wins a tie). Right after
     * the best one come names rebuilt from two neighbouring pieces, because OCR sometimes splits a
     * long name ("Department of Reg" + "Registration of Persons").
     */
    internal fun merchantCandidates(rows: List<List<OcrLine>>): List<String> {
        val scored = mutableListOf<Pair<Int, String>>()
        val pieces = mutableListOf<Pair<OcrLine, String>>()
        var candidateIndex = 0
        var afterTitle = false

        for (rowLines in rows.take(14)) {
            val followsTitle = afterTitle
            afterTitle = false
            for (line in rowLines) {
                val raw = line.text
                val withoutTitle = TITLE_PREFIX.replace(raw, "")
                if (withoutTitle.isBlank() && withoutTitle.length < raw.length) afterTitle = true
                val text = WELCOME.replace(withoutTitle, "").replace(MULTI_SPACE, " ").trim()
                    .trim('*', '-', '=', '.', ',', ':', ' ')
                if (text.isEmpty() || MERCHANT_SKIP.containsMatchIn(text) || text.contains(':')) continue
                val letters = text.count { it.isLetter() }
                val digits = text.count { it.isDigit() }
                if (letters < 3 || digits > letters / 2) continue
                if (amountsIn(text).isNotEmpty()) continue

                val words = WORD.findAll(text).map { it.value }.toList()
                val upperShare = text.count { it.isUpperCase() }.toDouble() / letters
                val lowercaseWords = words.count { it.first().isLowerCase() }
                var score = maxOf(0, 2 - candidateIndex)          // earlier lines are a little more likely
                if (upperShare >= 0.8 && letters >= 4) score += 3  // names are often printed in capitals
                if (ORGANISATION_WORDS.containsMatchIn(text)) score += 4
                if (followsTitle) score += 2                       // the line right under "Receipt" / "Invoice"
                if (words.size >= 3 && lowercaseWords >= words.size * 0.6) score -= 4  // running sentence text
                if (raw.trimEnd().endsWith('-')) score -= 2        // hyphenated word, i.e. printed paragraph
                if (words.size > 8) score -= 2
                candidateIndex++
                if (score >= 0) {
                    scored += score to text.take(60)
                    pieces += line to text
                }
            }
        }
        // sortedByDescending is stable, so equal scores keep their top-to-bottom order.
        val ranked = scored.sortedByDescending { it.first }.map { it.second }.distinct()
        val joined = pieces.flatMapIndexed { i, (a, textA) ->
            pieces.drop(i + 1).filter { (b, _) -> areNeighbours(a, b) }
                .map { (_, textB) -> joinPieces(textA, textB) }
        }.filter { it.length <= 60 }
        return (ranked.take(1) + joined + ranked.drop(1)).distinct()
    }

    /**
     * Two pieces of one name: same letter size, and either side by side on a line or one directly
     * under the other (a name wrapped onto two lines).
     */
    private fun areNeighbours(a: OcrLine, b: OcrLine): Boolean {
        // Addresses and branch numbers ("Pizza Hut # 127" / "Lerchenauer Strasse 42") are not
        // parts of a name.
        if (a.text.any { it.isDigit() } || b.text.any { it.isDigit() }) return false
        val h = maxOf(a.height, b.height).toDouble()
        if (minOf(a.height, b.height) < h * 0.7) return false
        val sideBySide = abs(a.centerY - b.centerY) < h * 0.6 &&
            minOf(abs(b.left - a.right), abs(a.left - b.right)) < h * 2
        val stacked = b.top - a.bottom in (-h * 0.3).toInt()..h.toInt() &&
            minOf(a.right, b.right) > maxOf(a.left, b.left)
        return sideBySide || stacked
    }

    /**
     * Joins two pieces of text, dropping the words they repeat: "Department of Reg" +
     * "Registration of Persons" gives "Department of Registration of Persons". The last word of
     * the first piece may be cut off ("Reg" of "Registration").
     */
    fun joinPieces(first: String, second: String): String {
        val a = first.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = second.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (a.isEmpty()) return b.joinToString(" ")
        if (b.isEmpty()) return a.joinToString(" ")
        for (k in minOf(a.size, b.size) downTo 1) {
            val tail = a.takeLast(k)
            val head = b.take(k)
            val fullWords = (0 until k - 1).all { tail[it].equals(head[it], ignoreCase = true) }
            val cut = tail[k - 1]
            val lastWord = cut.equals(head[k - 1], ignoreCase = true) ||
                (cut.length >= 3 && head[k - 1].startsWith(cut, ignoreCase = true))
            if (fullWords && lastWord) return (a.dropLast(k) + b).joinToString(" ")
        }
        return (a + b).joinToString(" ")
    }

    // ----------------------------------------------------------------- date

    private const val MONTH_ALT = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private val DATE_YMD = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val DATE_DMY = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?!\d)""")
    private val DATE_D_MON_Y = Regex(
        """(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s\-/.]*($MONTH_ALT)[a-z]*\.?[\s\-/.,]*(\d{4}|\d{2})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_MON_D_Y = Regex(
        """\b($MONTH_ALT)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    /** OCR slips in a year-first date: the second separator dropped or read as 1/l/I/|. */
    private val DATE_YMD_LOOSE = Regex("""(?<!\d)(20\d{2})\s?[-/.]\s?(\d{1,2})\s?[-/.1lI|]?\s?(\d{2})(?!\d)""")
    /** Both separators dropped ("20261028"); only trusted next to a "Date" label. */
    private val DATE_YMD_COMPACT = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)""")
    /** A time glued to the date, e.g. "2026/10/2809:05". */
    private val TIME_STUCK_TO_DATE = Regex("""(\d)(\d{1,2}:\d{2})""")

    internal fun findDate(rows: List<String>, today: LocalDate): LocalDate? = dateCandidates(rows, today).firstOrNull()

    internal fun dateCandidates(rows: List<String>, today: LocalDate): List<LocalDate> {
        val cleaned = rows.map { TIME_STUCK_TO_DATE.replace(it, "$1 $2") }
        // Rows that say "date" (and the row under them, where two-column forms put the value) are
        // the most reliable, then everything else; clean formats before OCR-damaged ones.
        val nearLabel = cleaned.indices.filter { cleaned[it].contains("date", ignoreCase = true) }
            .flatMap { listOfNotNull(cleaned[it], cleaned.getOrNull(it + 1)) }
        val ordered = nearLabel + cleaned
        val found = mutableListOf<LocalDate>()
        for (row in ordered) found += datesIn(row, today)
        for (row in ordered) {
            DATE_YMD_LOOSE.findAll(row).forEach { m -> validDate(m.int(1), m.int(2), m.int(3), today)?.let(found::add) }
        }
        for (row in nearLabel) {
            DATE_YMD_COMPACT.findAll(row).forEach { m -> validDate(m.int(1), m.int(2), m.int(3), today)?.let(found::add) }
        }
        return found.distinct()
    }

    private fun datesIn(row: String, today: LocalDate): List<LocalDate> {
        val found = mutableListOf<LocalDate>()
        DATE_YMD.findAll(row).forEach { m -> validDate(m.int(1), m.int(2), m.int(3), today)?.let(found::add) }
        DATE_D_MON_Y.findAll(row).forEach { m ->
            validDate(year(m.groupValues[3]), month(m.groupValues[2]), m.int(1), today)?.let(found::add)
        }
        DATE_MON_D_Y.findAll(row).forEach { m ->
            validDate(m.int(3), month(m.groupValues[1]), m.int(2), today)?.let(found::add)
        }
        DATE_DMY.findAll(row).forEach { m ->
            val a = m.int(1)
            val b = m.int(2)
            val y = year(m.groupValues[3])
            // Day-first (dd/mm/yyyy) is assumed; fall back to month-first when that is invalid.
            (validDate(y, b, a, today) ?: validDate(y, a, b, today))?.let(found::add)
        }
        return found
    }

    private fun MatchResult.int(group: Int) = groupValues[group].toInt()
    private fun year(s: String) = s.toInt().let { if (it < 100) 2000 + it else it }
    private fun month(s: String) = MONTH_ALT.split("|").indexOf(s.lowercase().take(3)) + 1

    private fun validDate(y: Int, m: Int, d: Int, today: LocalDate): LocalDate? =
        runCatching { LocalDate.of(y, m, d) }.getOrNull()
            ?.takeIf { !it.isAfter(today.plusDays(1)) && it.isAfter(today.minusYears(10)) }

    // ------------------------------------------------------- payment method

    internal fun findPaymentMethod(rows: List<String>): String? {
        val text = rows.joinToString("\n").lowercase()
        fun has(pattern: String) = Regex(pattern).containsMatchIn(text)
        return when {
            has("""\bvisa\b""") -> "Card (Visa)"
            has("""master\s*card|\bmaster\b""") -> "Card (Mastercard)"
            has("""\bamex\b|american express""") -> "Card (Amex)"
            has("""\b(credit|debit)\s*card\b|\bcard\b""") -> "Card"
            has("""\b(upi|qr|lankaqr|wallet|ezcash|frimi|genie)\b""") -> "Mobile / QR"
            has("""bank\s*transfer|\bneft\b|\brtgs\b|\bceft\b""") -> "Bank transfer"
            has("""\bcheque\b|\bcheck\s*no\b""") -> "Cheque"
            has("""\bcash\b""") -> "Cash"
            else -> null
        }
    }

    // ------------------------------------------------------------- category

    private val CATEGORY_HINTS: List<Pair<String, Regex>> = listOf(
        "Fuel" to """\b(fuel|petrol|diesel|filling station|ceypetco|lioc|octane)\b""",
        "Groceries" to """\b(supermarket|super|keells|cargills|food ?city|arpico|glomark|grocery|groceries|spar|lidl|aldi|walmart|tesco)\b""",
        "Dining" to """\b(restaurant|cafe|coffee|pizza|burger|kfc|mcdonald'?s|bakery|hotel|dine|kitchen|bistro)\b""",
        "Medical" to """\b(pharmacy|hospital|clinic|medical|chemist|healthguard|channell?ing)\b""",
        "Utilities" to """\b(electricity|ceb|leco|water board|dialog|mobitel|slt|hutch|airtel|telecom|broadband|internet)\b""",
        "Transport" to """\b(taxi|uber|pickme|bus|train|railway|parking|toll)\b""",
        "Travel" to """\b(airline|airways|flight|booking|guest house|resort)\b""",
        "Software & Subscriptions" to """\b(software|subscription|hosting|domain|cloud|saas|license|licence)\b""",
        "Office Supplies" to """\b(stationery|stationary|bookshop|printing|office supplies|toner)\b""",
        "Government & Fees" to """\b(department|ministry|government|municipal|secretariat|registration of persons|""" +
            """passport|immigration|motor traffic|stamp duty|finanzamt|b[üu]rgeramt|ausländerbehörde)\b""",
    ).map { (category, pattern) -> category to Regex(pattern, RegexOption.IGNORE_CASE) }

    internal fun guessCategory(rows: List<String>): String? {
        val text = rows.joinToString("\n")
        return CATEGORY_HINTS.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first
    }
}
