package com.billbook.app.rates

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Downloads daily exchange rates from the free, key-less currency-api
 * (github.com/fawazahmed0/exchange-api), which covers LKR and ~340 other currencies with history
 * from March 2024. Only the base currency and the date are sent.
 */
object RateFetcher {

    /** History starts here; earlier dates need a manual rate. */
    val FIRST_DAY: LocalDate = LocalDate.of(2024, 3, 2)

    private fun urls(base: String, date: String) = listOf(
        "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$date/v1/currencies/$base.min.json",
        "https://$date.currency-api.pages.dev/v1/currencies/$base.min.json",
    )

    /**
     * Rates for 1 [base] on [day], keyed by upper-case currency code.
     * @throws IOException when no mirror answers
     */
    fun fetch(base: String, day: LocalDate, today: LocalDate = LocalDate.now()): Map<String, Double> {
        val date = if (!day.isBefore(today)) "latest" else day.toString()
        var lastError: IOException? = null
        for (url in urls(base.lowercase(), date)) {
            try {
                val rates = parse(download(url), base)
                if (rates.isNotEmpty()) return rates
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No exchange rates for $base on $date")
    }

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private val PAIR = Regex(""""([a-z0-9]{2,10})"\s*:\s*(-?[0-9][0-9.eE+-]*)""")

    /** Parses {"date":"…","eur":{"lkr":369.93,…}}; plain regex keeps it testable without Android. */
    internal fun parse(json: String, base: String): Map<String, Double> {
        val start = json.indexOf("\"${base.lowercase()}\"")
        if (start < 0) return emptyMap()
        val body = json.substring(json.indexOf('{', start).takeIf { it >= 0 } ?: return emptyMap())
        return PAIR.findAll(body).mapNotNull { m ->
            m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 }?.let { m.groupValues[1].uppercase() to it }
        }.toMap()
    }
}
