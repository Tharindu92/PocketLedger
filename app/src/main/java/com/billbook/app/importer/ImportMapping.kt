package com.billbook.app.importer

import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import com.billbook.app.data.CategoryGuesser
import kotlin.math.abs

/** Turns statement lines into entries and spots bookings that are already recorded. Pure Kotlin. */
object ImportMapping {

    /** Category suggested from the payee and booking text; [history] comes from [CategoryGuesser.learn]. */
    fun guessCategory(
        line: StatementLine,
        type: TxType,
        history: Map<Pair<TxType, String>, String> = emptyMap(),
    ): String = CategoryGuesser.guess(type, listOf(line.party, line.reference, line.kind), history)

    /** [category] null means use the guess. */
    fun toTransaction(line: StatementLine, type: TxType, profileId: Long, category: String? = null): Transaction {
        val charged = abs(line.amountCents)
        // Card payment abroad: the purchase amount is the entry, the account amount is what was paid.
        val foreign = line.originalAmountCents != null && line.originalCurrency != null
        return Transaction(
            profileId = profileId,
            type = type,
            amountCents = if (foreign) line.originalAmountCents!! else charged,
            currency = if (foreign) line.originalCurrency!! else line.currency,
            dateEpochDay = line.date.toEpochDay(),
            party = line.party.take(60),
            category = category ?: guessCategory(line, type),
            paymentMethod = paymentMethod("${line.kind} ${line.reference}"),
            notes = listOf(line.reference, line.kind).filter { it.isNotBlank() && it != line.party }
                .joinToString(" · ").take(200),
            paidAmountCents = if (foreign) charged else null,
            paidCurrency = if (foreign) line.currency else null,
        )
    }

    private fun paymentMethod(text: String): String {
        val t = text.lowercase()
        return when {
            Regex("""mastercard|visa|card|karte|debit card|apple pay|google pay""").containsMatchIn(t) -> "Card"
            Regex("""transfer|überweisung|lastschrift|direct debit|sepa|standing order|dauerauftrag""").containsMatchIn(t) -> "Bank transfer"
            else -> ""
        }
    }

    /**
     * True when an entry with the same amount (as recorded, or as paid) and direction is already
     * saved within [DAYS] days, e.g. added from the bank notification or scanned from the bill.
     */
    fun isDuplicate(line: StatementLine, existing: List<Transaction>): Boolean {
        val charged = abs(line.amountCents)
        val type = if (line.amountCents > 0) TxType.INCOME else TxType.EXPENSE
        val day = line.date.toEpochDay()
        return existing.any { t ->
            t.type == type && abs(t.dateEpochDay - day) <= DAYS && (
                (t.amountCents == charged && t.currency == line.currency) ||
                    (t.paidAmountCents == charged && t.paidCurrency == line.currency) ||
                    (line.originalAmountCents != null && t.amountCents == line.originalAmountCents &&
                        t.currency == line.originalCurrency)
                )
        }
    }

    const val DAYS = 3L
}
