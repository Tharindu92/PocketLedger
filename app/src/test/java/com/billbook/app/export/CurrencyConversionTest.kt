package com.billbook.app.export

import com.billbook.app.data.ExchangeRate
import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import com.billbook.app.rates.RateFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class CurrencyConversionTest {

    private val oct28 = LocalDate.of(2026, 10, 28)
    private val nov1 = LocalDate.of(2026, 11, 1)

    private fun tx(id: Long, cents: Long, currency: String, day: LocalDate, tax: Long = 0, paid: Long? = null, paidIn: String? = null) =
        Transaction(
            id = id, type = TxType.EXPENSE, amountCents = cents, currency = currency, taxCents = tax,
            dateEpochDay = day.toEpochDay(), party = "Shop $id", paidAmountCents = paid, paidCurrency = paidIn,
        )

    private fun rate(quote: String, day: LocalDate?, value: Double, manual: Boolean = false) =
        ExchangeRate("EUR", quote, day?.toEpochDay() ?: ExchangeRate.ANY_DAY, value, manual)

    @Test
    fun paidAmountWinsOverExchangeRate() {
        // 2,500 LKR bill paid with a EUR card: the 7.42 EUR actually charged is used.
        val t = tx(1, 250000, "LKR", oct28, tax = 25000, paid = 742, paidIn = "EUR")
        val (converted, how) = CurrencyConverter.convert(t, "EUR", "EUR", RateTable(listOf(rate("LKR", oct28, 369.94))))!!
        assertEquals(742L, converted.amountCents)
        assertEquals(74L, converted.taxCents)           // tax scaled by the same ratio
        assertEquals("EUR", converted.currency)
        assertEquals(ConversionMethod.PAID, how!!.method)
        assertEquals(250000L, how.originalAmountCents)
    }

    @Test
    fun exchangeRateOfTheBillDate() {
        val t = tx(1, 250000, "LKR", oct28)
        val rates = RateTable(listOf(rate("LKR", oct28, 369.94), rate("LKR", nov1, 371.40)))
        val (converted, how) = CurrencyConverter.convert(t, "EUR", "EUR", rates)!!
        assertEquals(676L, converted.amountCents)       // 2500 / 369.94 = 6.758 → 6.76
        assertEquals(ConversionMethod.RATE, how!!.method)
        assertEquals(oct28, how.rateDay)
    }

    @Test
    fun nearestRateWithinAWeekThenManualRate() {
        val rates = RateTable(
            listOf(rate("LKR", oct28.minusDays(2), 368.0), rate("LKR", oct28.plusDays(2), 372.0), rate("LKR", null, 400.0, manual = true))
        )
        assertEquals(368.0, rates.find("LKR", oct28.toEpochDay())!!.rate, 0.0)   // ties prefer the day before
        assertEquals(400.0, rates.find("LKR", oct28.plusDays(30).toEpochDay())!!.rate, 0.0)
        assertNull(RateTable(emptyList()).find("LKR", oct28.toEpochDay()))
    }

    @Test
    fun sameCurrencyNeedsNoConversion() {
        val (converted, how) = CurrencyConverter.convert(tx(1, 500, "", oct28), "EUR", "EUR", RateTable(emptyList()))!!
        assertEquals(500L, converted.amountCents)
        assertNull(how)
    }

    @Test
    fun combinedStatementListsWhatCouldNotBeConverted() {
        val items = listOf(
            tx(1, 1000, "EUR", oct28),
            tx(2, 250000, "LKR", oct28),
            tx(3, 5000, "USD", oct28),                  // no USD rate
            tx(4, 999, "INR", oct28),                   // currency not selected
        )
        val data = CurrencyConverter.assemble(
            from = oct28, to = nov1, items = items, currencies = setOf("EUR", "LKR", "USD"), report = "EUR",
            defaultCurrency = "EUR", rates = listOf(rate("LKR", oct28, 250.0)), businessName = "", profileName = "Personal",
        )
        assertEquals(listOf(1L, 2L), data.transactions.map { it.id })
        assertEquals(2000L, data.totalExpense)          // 10.00 EUR + 2,500 LKR / 250
        assertEquals(listOf(3L), data.unconverted.map { it.id })
        assertTrue(data.isCombined)

        val csv = ByteArrayOutputStream().also { StatementExporter.write(data, ExportFormat.CSV, it) }.toString("UTF-8")
        val rows = CsvReader.parse(csv)
        assertEquals("Converted With", rows[0].last())
        assertEquals(listOf("2500.00", "LKR", "Rate 1 EUR = 250 LKR (2026-10-28)"), rows[2].takeLast(3))
        assertEquals("Not included: no exchange rate", rows[3].last())
    }

    @Test
    fun singleCurrencyCsvIsUnchanged() {
        val data = CurrencyConverter.assemble(oct28, nov1, listOf(tx(1, 1000, "EUR", oct28)), setOf("EUR"), "EUR", "EUR", emptyList(), "", "")
        val csv = ByteArrayOutputStream().also { StatementExporter.write(data, ExportFormat.CSV, it) }.toString("UTF-8")
        assertEquals("Notes", CsvReader.parse(csv)[0].last())
    }

    @Test
    fun parsesDownloadedRates() {
        val json = """{"date":"2026-10-28","eur":{"1inch":4.1,"lkr":369.93580483,"usd":1.11219528,"zwl":0}}"""
        val rates = RateFetcher.parse(json, "EUR")
        assertEquals(369.93580483, rates["LKR"]!!, 1e-9)
        assertEquals(1.11219528, rates["USD"]!!, 1e-9)
        assertNull(rates["ZWL"])                         // zero rates are ignored
        assertTrue(RateFetcher.parse("""{"error":"not found"}""", "EUR").isEmpty())
    }
}
