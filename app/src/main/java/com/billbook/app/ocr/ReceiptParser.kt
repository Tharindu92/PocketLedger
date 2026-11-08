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
        val rows = groupIntoRows(lines)
        val total = findTotal(rows)
        return ParsedReceipt(
            totalCents = total,
            taxCents = findTax(rows, total),
            billNumber = findBillNumber(rows),
            merchant = findMerchant(rows),
            date = findDate(rows, today),
            paymentMethod = findPaymentMethod(rows),
            category = guessCategory(rows),
            currency = detectCurrency(rows, homeCurrency),
        )
    }

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

    internal fun groupIntoRows(lines: List<OcrLine>): List<String> {
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
        return rows.map { row -> row.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }

    // ------------------------------------------------------------- amounts

    // 1,234.56 | 1234.56 | 1234,56 — but not dates (12.05.24), percentages (18.00%) or long ids.
    private val DECIMAL_AMOUNT =
        Regex("""(?<!\d)(?<!\d[.,])(\d{1,3}(?:,\d{3})+|\d{1,9})[.,](\d{2})(?!\d)(?![.,]\d)(?!\s*%)""")
    private val INTEGER_AMOUNT = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d{1,9})(?![\d.,])(?!\s*%)""")
    private val CURRENCY_TOKENS = Regex("""(?i)\b(rs|lkr|slr|usd|inr|eur|gbp|aud|sgd|cad|nzd|aed|myr|rm|pkr)\b|[$€£₹¥฿]""")

    internal fun amountsIn(row: String): List<Long> =
        DECIMAL_AMOUNT.findAll(row).mapNotNull { m ->
            val whole = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
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

    internal fun findTotal(rows: List<String>): Long? {
        var bestRank = Int.MAX_VALUE
        var best: Long? = null
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

            if (amount != null && amount > 0 &&
                (rank < bestRank || (rank == bestRank && amount > (best ?: 0)))
            ) {
                bestRank = rank
                best = amount
            }
        }
        if (best != null) return best

        // No "total" label recognised: use the largest amount, ignoring cash/change lines.
        return rows
            .filterNot { row -> row.lowercase().let { l -> FALLBACK_EXCLUDE.any { l.contains(it) } } }
            .flatMap { amountsIn(it) }
            .maxOrNull()
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

    private val BILL_NO = Regex(
        """\b(?:invoice|inv|bill|receipt|rcpt|ref|order|txn|trans|transaction|voucher|doc)\b\.?\s*""" +
            """(?:no|num|number|nbr|id|#)?\.?\s*[:#\-]?\s*#?\s*([A-Za-z0-9][A-Za-z0-9\-/]{2,24})""",
        RegexOption.IGNORE_CASE,
    )
    private val LOOKS_LIKE_DATE = Regex("""^\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}$""")

    internal fun findBillNumber(rows: List<String>): String? {
        for (row in rows) {
            for (m in BILL_NO.findAll(row)) {
                val value = m.groupValues[1].trimEnd('-', '/')
                if (value.any { it.isDigit() } && !LOOKS_LIKE_DATE.matches(value)) return value
            }
        }
        return null
    }

    // ------------------------------------------------------------- merchant

    private val MERCHANT_SKIP = Regex(
        """\b(receipt|invoice|tax|vat|gst|tel|phone|fax|mobile|hotline|date|time|cashier|counter|""" +
            """terminal|pos|thank|bill|order|table|www|http|email|reg|no)\b|@|\.com|\.lk""",
        RegexOption.IGNORE_CASE,
    )
    private val WELCOME = Regex("""^\s*welcome\s+to\s+""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s{2,}""")

    internal fun findMerchant(rows: List<String>): String? {
        for (raw in rows.take(8)) {
            val row = WELCOME.replace(raw, "").replace(MULTI_SPACE, " ").trim()
                .trim('*', '-', '=', '.', ',', ':', ' ')
            if (row.isEmpty() || MERCHANT_SKIP.containsMatchIn(row)) continue
            val letters = row.count { it.isLetter() }
            val digits = row.count { it.isDigit() }
            if (letters < 3 || digits > letters / 2) continue
            if (amountsIn(row).isNotEmpty()) continue
            return row.take(60)
        }
        return null
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

    internal fun findDate(rows: List<String>, today: LocalDate): LocalDate? {
        // Rows that say "date" are the most reliable, then everything else.
        val ordered = rows.filter { it.contains("date", ignoreCase = true) } + rows
        for (row in ordered) dateIn(row, today)?.let { return it }
        return null
    }

    private fun dateIn(row: String, today: LocalDate): LocalDate? {
        DATE_YMD.findAll(row).forEach { m ->
            validDate(m.int(1), m.int(2), m.int(3), today)?.let { return it }
        }
        DATE_D_MON_Y.findAll(row).forEach { m ->
            validDate(year(m.groupValues[3]), month(m.groupValues[2]), m.int(1), today)?.let { return it }
        }
        DATE_MON_D_Y.findAll(row).forEach { m ->
            validDate(m.int(3), month(m.groupValues[1]), m.int(2), today)?.let { return it }
        }
        DATE_DMY.findAll(row).forEach { m ->
            val a = m.int(1)
            val b = m.int(2)
            val y = year(m.groupValues[3])
            // Day-first (dd/mm/yyyy) is assumed; fall back to month-first when that is invalid.
            (validDate(y, b, a, today) ?: validDate(y, a, b, today))?.let { return it }
        }
        return null
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
    ).map { (category, pattern) -> category to Regex(pattern, RegexOption.IGNORE_CASE) }

    internal fun guessCategory(rows: List<String>): String? {
        val text = rows.joinToString("\n")
        return CATEGORY_HINTS.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first
    }
}
