package com.billbook.app.export

import com.billbook.app.data.ExchangeRate
import com.billbook.app.data.Transaction
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

/** How an entry's amount got into the report currency. */
enum class ConversionMethod {
    /** Paid amount recorded on the entry: what the card or bank actually charged. */
    PAID,
    /** Downloaded exchange rate for the bill date (or the nearest day before it). */
    RATE,
    /** Exchange rate typed in by the user. */
    MANUAL_RATE,
}

/** Shown next to converted entries so readers can tell exact amounts from estimates. */
data class Conversion(
    val method: ConversionMethod,
    val originalAmountCents: Long,
    val originalCurrency: String,
    /** 1 report currency = [rate] original currency; null for [ConversionMethod.PAID]. */
    val rate: Double? = null,
    /** Day the rate is from; null for paid amounts and manual rates. */
    val rateDay: LocalDate? = null,
)

/** Exchange rates with one base (the report currency), looked up by quote currency and day. */
class RateTable(rates: List<ExchangeRate>) {
    private val byQuote = rates.groupBy { it.quote }

    /**
     * The rate for [day], else the nearest one up to [MAX_GAP_DAYS] before or after (rates are not
     * published for every day everywhere), else a manual rate for any day.
     */
    fun find(quote: String, day: Long): ExchangeRate? {
        val forQuote = byQuote[quote] ?: return null
        val dated = forQuote.filter { it.day != ExchangeRate.ANY_DAY && abs(it.day - day) <= MAX_GAP_DAYS }
        // Exact day first, then the closest, preferring the day before over the day after.
        return dated.minWithOrNull(compareBy({ abs(it.day - day) }, { if (it.day <= day) 0 else 1 }))
            ?: forQuote.firstOrNull { it.day == ExchangeRate.ANY_DAY }
    }

    companion object {
        const val MAX_GAP_DAYS = 7L
    }
}

object CurrencyConverter {

    /**
     * Converts [t] into [report]. Returns the entry with amount, tax and currency in the report
     * currency plus how it was converted (null when no conversion was needed), or null when there
     * is neither a paid amount in [report] nor an exchange rate.
     *
     * @param defaultCurrency currency of old entries saved without one
     */
    fun convert(t: Transaction, report: String, defaultCurrency: String, rates: RateTable): Pair<Transaction, Conversion?>? {
        val original = t.currency.ifBlank { defaultCurrency }
        if (original == report) return t.copy(currency = report) to null

        val paid = t.paidAmountCents
        if (paid != null && t.paidCurrency == report) {
            val tax = if (t.amountCents == 0L) 0L else (t.taxCents.toDouble() * paid / t.amountCents).roundToLong()
            return t.copy(amountCents = paid, taxCents = tax, currency = report) to
                Conversion(ConversionMethod.PAID, t.amountCents, original)
        }

        val rate = rates.find(original, t.dateEpochDay) ?: return null
        if (rate.rate <= 0.0) return null
        val converted = t.copy(
            amountCents = (t.amountCents / rate.rate).roundToLong(),
            taxCents = (t.taxCents / rate.rate).roundToLong(),
            currency = report,
        )
        return converted to Conversion(
            method = if (rate.manual) ConversionMethod.MANUAL_RATE else ConversionMethod.RATE,
            originalAmountCents = t.amountCents,
            originalCurrency = original,
            rate = rate.rate,
            rateDay = rate.day.takeIf { !rate.manual }?.let(LocalDate::ofEpochDay),
        )
    }

    /**
     * Builds a statement in [report] from entries in [currencies]. Entries that cannot be converted
     * are listed in [StatementData.unconverted] instead of being guessed or silently dropped.
     */
    fun assemble(
        from: LocalDate,
        to: LocalDate,
        items: List<Transaction>,
        currencies: Set<String>,
        report: String,
        defaultCurrency: String,
        rates: List<ExchangeRate>,
        businessName: String,
        profileName: String,
    ): StatementData {
        val table = RateTable(rates.filter { it.base == report })
        val included = mutableListOf<Transaction>()
        val conversions = mutableMapOf<Long, Conversion>()
        val unconverted = mutableListOf<Transaction>()
        for (t in items) {
            if (t.currency.ifBlank { defaultCurrency } !in currencies) continue
            val result = convert(t, report, defaultCurrency, table)
            if (result == null) {
                unconverted += t
            } else {
                included += result.first
                result.second?.let { conversions[t.id] = it }
            }
        }
        return StatementData(
            from, to, report, businessName, included, profileName,
            conversions = conversions,
            unconverted = unconverted.sortedWith(compareBy({ it.dateEpochDay }, { it.id })),
            sourceCurrencies = currencies.sorted(),
        )
    }
}
