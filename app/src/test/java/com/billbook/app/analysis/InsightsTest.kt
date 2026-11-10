package com.billbook.app.analysis

import com.billbook.app.data.ExchangeRate
import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class InsightsTest {

    private var nextId = 1L
    private fun tx(
        type: TxType, cents: Long, date: String, category: String = "", party: String = "",
        currency: String = "EUR", paid: Long? = null, paidIn: String? = null,
    ) = Transaction(
        id = nextId++, type = type, amountCents = cents, currency = currency,
        dateEpochDay = LocalDate.parse(date).toEpochDay(), category = category, party = party,
        paidAmountCents = paid, paidCurrency = paidIn,
    )

    private val sep = YearMonth.of(2026, 9)
    private val oct = YearMonth.of(2026, 10)

    @Test
    fun monthsCategoriesAndSavingsGrowth() {
        val items = listOf(
            tx(TxType.INCOME, 300000, "2026-09-25", "Salary", "ACME"),
            tx(TxType.EXPENSE, 5000, "2026-09-03", "Groceries", "REWE"),
            tx(TxType.EXPENSE, 3000, "2026-09-10", "Groceries", "Lidl"),
            tx(TxType.EXPENSE, 400000, "2026-10-01", "Rent", "Hausverwaltung"),
            tx(TxType.EXPENSE, 2000, "2026-10-12", "", "Kiosk"),
        )
        val previous = listOf(tx(TxType.EXPENSE, 4000, "2026-08-02", "Groceries", "REWE"))
        val d = Insights.build(sep, oct, items, previous, "EUR", "EUR", emptyList())

        assertEquals(listOf(MonthFlow(sep, 300000, 8000), MonthFlow(oct, 0, 402000)), d.months)
        assertEquals(listOf(292000L, -110000L), d.savingsGrowth)
        assertEquals(-110000L, d.net)
        assertEquals(listOf("Rent", "Groceries", Insights.UNCATEGORISED), d.expenseCategories.map { it.category })

        val groceries = d.expenseCategories[1]
        assertEquals(8000L, groceries.cents)
        assertEquals(2, groceries.count)
        assertEquals(4000L, groceries.previousCents)
        assertEquals(1f, Insights.change(groceries.cents, groceries.previousCents))
        assertEquals(listOf("REWE" to 5000L, "Lidl" to 3000L), groceries.topPayees)
        assertEquals(0L, d.expenseCategories[0].previousCents)
        assertNull(Insights.change(d.expenseCategories[0].cents, 0))
    }

    @Test
    fun investingCountsAsSavedNotSpent() {
        val items = listOf(
            tx(TxType.INCOME, 300000, "2026-10-25", "Salary"),
            tx(TxType.EXPENSE, 100000, "2026-10-26", "Rent"),
            tx(TxType.EXPENSE, 50000, "2026-10-27", "Investments", "Trade Republic"),
        )
        val d = Insights.build(oct, oct, items, null, "EUR", "EUR", emptyList())
        assertEquals(100000L, d.expense)
        assertEquals(50000L, d.invested)
        assertEquals(200000L, d.net)
        assertEquals(listOf("Rent"), d.expenseCategories.map { it.category })
        assertEquals(50000L, d.months.single().invested)
    }

    @Test
    fun otherCurrenciesAreConvertedOrListed() {
        val items = listOf(
            tx(TxType.EXPENSE, 250000, "2026-10-05", "Government & Fees", currency = "LKR", paid = 742, paidIn = "EUR"),
            tx(TxType.EXPENSE, 10000, "2026-10-06", "Dining", currency = "USD"),
            tx(TxType.EXPENSE, 2000, "2026-10-07", "Dining", currency = "GBP"),
        )
        val rates = listOf(ExchangeRate("EUR", "USD", LocalDate.parse("2026-10-06").toEpochDay(), 1.25))
        val d = Insights.build(oct, oct, items, null, "EUR", "EUR", rates)
        assertEquals(742L + 8000L, d.expense)
        assertEquals(listOf("GBP"), d.unconverted.map { it.currency })
        assertNull(d.previousExpense)
    }
}
