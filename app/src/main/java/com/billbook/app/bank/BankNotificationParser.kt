package com.billbook.app.bank

import com.billbook.app.data.TxType
import com.billbook.app.ocr.ReceiptParser

/** Money movement found in a notification. */
data class BankAlert(
    val type: TxType,
    val amountCents: Long,
    /** ISO code, or null when the notification shows no currency. */
    val currency: String?,
    /** Sender (income) or merchant / recipient (expense), when it can be read. */
    val party: String?,
)

/**
 * Reads banking / payment notifications such as
 *  - "You received €50.00 from Max Mustermann"            (N26, English)
 *  - "Du hast 1.250,00 € von Erika Musterfrau erhalten."  (N26, German)
 *  - "Rs.15,000.00 credited to your account via CEFT from K PERERA" (bank SMS)
 *  - "You paid €12.40 at REWE"                             (card payment → expense)
 *
 * Pure Kotlin so it can be unit-tested. Wording differs per bank and language, so this
 * relies on keywords plus "amount next to a currency".
 */
object BankNotificationParser {

    private const val D = "\\$"
    private val ISO = ReceiptParser.ISO_CODES.joinToString("|")

    /** Currency marker: specific dollars first, then rupee, symbols and ISO codes. */
    private val CUR = "US$D|NZ$D|HK$D|S$D|A$D|C$D|\\b[Rr][Ss](?![A-Za-z])\\.?|[€$D£₹¥฿]|\\b(?:$ISO)\\b"

    /** 1.250,00 | 1,250.00 | 1 250,00 | 50 | 12.4 */
    private const val NUM = "\\d{1,3}(?:[.,\\u00A0\\u202F ]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?"

    private val CUR_THEN_NUM = Regex("($CUR)\\s?((?:$NUM))(?!\\d)")
    private val NUM_THEN_CUR = Regex("(?<![\\d.,])((?:$NUM))\\s?($CUR)")
    private val PLAIN_DECIMAL = Regex("(?<![\\d.,])(\\d{1,3}(?:[.,]\\d{3})*[.,]\\d{2})(?!\\d)")

    private val INCOME = Regex(
        "(?i)\\b(received|credited|deposited|deposit|incoming|sent you|paid you|transferred you|money in|" +
            "erhalten|bekommen|gutschrift|eingegangen|zahlungseingang|hat dir|" +
            "reçu|recibido|recibiste|ricevuto|ontvangen)\\b"
    )
    private val EXPENSE = Regex(
        "(?i)\\b(paid|spent|debited|purchase|payment|you sent|sent to|withdrawal|withdrawn|charged|" +
            "bezahlt|gezahlt|ausgegeben|kartenzahlung|abbuchung|lastschrift|abgehoben|gesendet|überwiesen)\\b"
    )
    /** Someone else received *your* money: "Trading 212 EU GmbH has received your bank transfer of €2". */
    private val THEY_RECEIVED_YOURS = Regex(
        "(?i)\\b(?:has|have)\\s+received\\s+your\\b|\\bhat\\s+(?:deine|ihre)\\b.*\\b(?:erhalten|empfangen)\\b"
    )

    /** Not money movements: codes, failures, requests, reminders. */
    private val IGNORE = Regex(
        "(?i)\\b(otp|code|verification|verify|password|passcode|log ?in|sign ?in|declined|failed|" +
            "unsuccessful|rejected|reversed|abgelehnt|fehlgeschlagen|requested|requests|request|anfrage|" +
            "fordert|reminder|erinnerung)\\b"
    )
    private val BALANCE = Regex("(?i)\\b(balance|bal|available|avl|saldo|kontostand|guthaben)\\b")

    private const val STOP = "[.,!:;()\\n]|$"
    private val INCOME_PARTY = listOf(
        Regex("(?i)\\bfrom\\s+(.+?)(?=\\s+(?:to|on|via|for|into|in|with|at|ref|reference)\\b|$STOP)"),
        Regex("(?i)\\bvon\\s+(.+?)(?=\\s+(?:erhalten|bekommen|gesendet|überwiesen|auf|am|für|mit)\\b|$STOP)"),
        Regex("(?i)^(.+?)\\s+(?:has\\s+)?(?:sent|paid|transferred)\\s+you\\b"),
        Regex("(?i)^(.+?)\\s+hat\\s+dir\\b"),
    )
    private val EXPENSE_PARTY = listOf(
        Regex("(?i)^(.+?)\\s+(?:has|have)\\s+received\\s+your\\b"),
        Regex("(?i)^(.+?)\\s+hat\\s+(?:deine|ihre)\\b"),
        Regex("(?i)\\b(?:at|to)\\s+(.+?)(?=\\s+(?:on|via|for|with|from|using)\\b|$STOP)"),
        Regex("(?i)\\b(?:bei|an)\\s+(.+?)(?=\\s+(?:bezahlt|gezahlt|überwiesen|gesendet|am|mit)\\b|$STOP)"),
    )
    private val NOT_A_PARTY = Regex(
        "(?i)^(your|you|the|my|a|an|account|card|konto|karte|dein|deinem|deine|ihr|ihrem)\\b"
    )

    /**
     * @param homeCurrency resolves ambiguous symbols ("Rs", "$").
     * @param detectExpenses also return outgoing payments; incomes are always returned.
     */
    fun parse(title: String, text: String, homeCurrency: String, detectExpenses: Boolean): BankAlert? {
        val full = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")
        if (full.isBlank() || IGNORE.containsMatchIn(full)) return null

        val type = when {
            THEY_RECEIVED_YOURS.containsMatchIn(full) -> if (detectExpenses) TxType.EXPENSE else return null
            INCOME.containsMatchIn(full) -> TxType.INCOME
            detectExpenses && EXPENSE.containsMatchIn(full) -> TxType.EXPENSE
            else -> return null
        }
        val (amount, currency) = findAmount(text, homeCurrency)
            ?: findAmount(full, homeCurrency)
            ?: return null
        if (amount <= 0) return null

        val party = findParty(type, text) ?: findParty(type, "$title $text")
        return BankAlert(type, amount, currency, party)
    }

    private fun findAmount(s: String, homeCurrency: String): Pair<Long, String?>? {
        data class Hit(val start: Int, val number: String, val currencyToken: String?)

        val hits = CUR_THEN_NUM.findAll(s).map { Hit(it.range.first, it.groupValues[2], it.groupValues[1]) } +
            NUM_THEN_CUR.findAll(s).map { Hit(it.range.first, it.groupValues[1], it.groupValues[2]) }
        val withCurrency = hits.sortedBy { it.start }.firstOrNull { !isBalance(s, it.start) }
        if (withCurrency != null) {
            val cents = parseAmount(withCurrency.number) ?: return null
            val code = ReceiptParser.detectCurrency(listOf(withCurrency.currencyToken.orEmpty()), homeCurrency)
            return cents to code
        }
        // No currency next to the number: accept only a clear decimal amount.
        val plain = PLAIN_DECIMAL.findAll(s).firstOrNull { !isBalance(s, it.range.first) } ?: return null
        val cents = parseAmount(plain.groupValues[1]) ?: return null
        return cents to ReceiptParser.detectCurrency(listOf(s), homeCurrency)
    }

    /** True when the amount is introduced as a balance ("Available balance: €1,000"). */
    private fun isBalance(s: String, start: Int): Boolean =
        BALANCE.containsMatchIn(s.substring((start - 25).coerceAtLeast(0), start))

    /** Handles both 1,250.00 and 1.250,00 styles. */
    internal fun parseAmount(raw: String): Long? {
        val s = raw.replace(Regex("[\\u00A0\\u202F ]"), "")
        val lastSep = s.indexOfLast { it == '.' || it == ',' }
        val decimals = if (lastSep >= 0) s.length - lastSep - 1 else 0
        val (whole, fraction) = if (lastSep >= 0 && decimals in 1..2) {
            s.substring(0, lastSep) to s.substring(lastSep + 1)
        } else {
            s to ""
        }
        val w = whole.replace(".", "").replace(",", "").toLongOrNull() ?: return null
        val f = fraction.padEnd(2, '0').toLongOrNull() ?: 0L
        return w * 100 + f
    }

    private fun findParty(type: TxType, s: String): String? {
        val patterns = if (type == TxType.INCOME) INCOME_PARTY else EXPENSE_PARTY
        for (regex in patterns) {
            for (m in regex.findAll(s)) {
                val candidate = m.groupValues[1].trim().trim('"', '\'', '-', ' ')
                val letters = candidate.count { it.isLetter() }
                val digits = candidate.count { it.isDigit() }
                if (letters >= 2 && digits <= letters && !NOT_A_PARTY.containsMatchIn(candidate)) {
                    return candidate.take(60)
                }
            }
        }
        return null
    }
}
