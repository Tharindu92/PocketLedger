package com.billbook.app.rates

import com.billbook.app.data.ExchangeRate
import com.billbook.app.data.ExchangeRateDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/** Downloads the exchange rates a statement is missing and keeps them on the device. */
class ExchangeRateRepository(private val dao: ExchangeRateDao) {

    /**
     * Fetches 1 [base] = x [quote] for each (quote, day) in [needed], one request per day.
     * @return how many days were downloaded
     * @throws IOException when every request failed (e.g. no internet)
     */
    suspend fun download(base: String, needed: Collection<Pair<String, LocalDate>>): Int = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val byDay = needed
            .filter { (quote, day) -> quote != base && !day.isBefore(RateFetcher.FIRST_DAY) }
            .groupBy({ it.second }, { it.first })
        var downloaded = 0
        var lastError: IOException? = null
        for ((day, quotes) in byDay.entries.sortedBy { it.key }.take(MAX_DAYS_PER_RUN)) {
            try {
                val rates = RateFetcher.fetch(base, minOf(day, today), today)
                val rows = quotes.distinct().mapNotNull { quote ->
                    rates[quote]?.let { ExchangeRate(base, quote, day.toEpochDay(), it) }
                }
                dao.upsertAll(rows)
                downloaded++
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (downloaded == 0 && lastError != null) throw lastError
        downloaded
    }

    /** A rate the user typed: 1 [base] = [rate] [quote], used for any date without a downloaded rate. */
    suspend fun setManual(base: String, quote: String, rate: Double) =
        dao.upsertAll(listOf(ExchangeRate(base, quote, ExchangeRate.ANY_DAY, rate, manual = true)))

    private companion object {
        const val MAX_DAYS_PER_RUN = 400
    }
}
