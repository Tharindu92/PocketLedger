package com.billbook.app.analysis

import com.billbook.app.data.Categories
import com.billbook.app.data.ExchangeRate
import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import com.billbook.app.export.CurrencyConverter
import com.billbook.app.export.RateTable
import java.time.LocalDate
import java.time.YearMonth

/** Money in and out of one calendar month, in the analysis currency. */
data class MonthFlow(val month: YearMonth, val income: Long, val expense: Long, val invested: Long = 0) {
    /** Kept this month, including what was invested. */
    val net: Long get() = income - expense
}

/** Total of one category in the period, compared with the period before. */
data class CategoryTotal(
    val category: String,
    val cents: Long,
    /** Part of all income or all expenses, 0..1. */
    val share: Float,
    val count: Int,
    /** Same category in the period before; null when there is no period before (all time). */
    val previousCents: Long?,
    /** Biggest payees in this category, largest first. */
    val topPayees: List<Pair<String, Long>>,
)

data class InsightsData(
    val currency: String,
    val months: List<MonthFlow>,
    val income: Long,
    /** Spending only: money moved into [Categories.INVESTMENTS] is in [invested], not here. */
    val expense: Long,
    val invested: Long,
    val previousIncome: Long?,
    val previousExpense: Long?,
    val expenseCategories: List<CategoryTotal>,
    val incomeCategories: List<CategoryTotal>,
    /** Savings at the end of each month of [months], counted from the start of the period. */
    val savingsGrowth: List<Long>,
    /** Entries in other currencies left out because no paid amount or exchange rate was found. */
    val unconverted: List<Transaction>,
) {
    val net: Long get() = income - expense
    /** Part of income that was kept, or null without income. */
    val savingsRate: Float? get() = if (income > 0) net.toFloat() / income else null
    val averageMonthlyExpense: Long get() = if (months.isEmpty()) 0 else expense / months.size
    val isEmpty: Boolean get() = income == 0L && expense == 0L && invested == 0L
}

object Insights {

    const val UNCATEGORISED = "Uncategorised"
    private const val TOP_PAYEES = 5

    /**
     * Totals for the months [from]..[to] in [currency]. Entries in other currencies are converted
     * with their paid amount or a stored exchange rate, as on statements.
     *
     * @param items entries from the start of [from] to the end of [to]
     * @param previousItems entries of the same-length period before, or null to skip comparisons
     */
    fun build(
        from: YearMonth,
        to: YearMonth,
        items: List<Transaction>,
        previousItems: List<Transaction>?,
        currency: String,
        defaultCurrency: String,
        rates: List<ExchangeRate>,
    ): InsightsData {
        val table = RateTable(rates.filter { it.base == currency })
        val unconverted = mutableListOf<Transaction>()
        fun convert(list: List<Transaction>, track: Boolean) = list.mapNotNull { t ->
            val converted = CurrencyConverter.convert(t, currency, defaultCurrency, table)?.first
            if (converted == null && track) unconverted += t
            converted
        }
        val current = convert(items, track = true)
        val previous = previousItems?.let { convert(it, track = false) }

        val byMonth = current.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) }
        val months = generateSequence(from) { it.plusMonths(1) }.takeWhile { it <= to }.map { m ->
            val list = byMonth[m].orEmpty()
            MonthFlow(m, list.sumOf(TxType.INCOME), list.sumOf(TxType.EXPENSE), list.invested())
        }.toList()

        return InsightsData(
            currency = currency,
            months = months,
            income = current.sumOf(TxType.INCOME),
            expense = current.sumOf(TxType.EXPENSE),
            invested = current.invested(),
            previousIncome = previous?.sumOf(TxType.INCOME),
            previousExpense = previous?.sumOf(TxType.EXPENSE),
            expenseCategories = categories(current, previous, TxType.EXPENSE),
            incomeCategories = categories(current, previous, TxType.INCOME),
            savingsGrowth = months.runningFold(0L) { total, m -> total + m.net }.drop(1),
            unconverted = unconverted.sortedBy { it.dateEpochDay },
        )
    }

    /** Investing is keeping money, not spending it. */
    private fun isInvestment(t: Transaction) =
        t.type == TxType.EXPENSE && t.category.trim().equals(Categories.INVESTMENTS, ignoreCase = true)

    /** Income, or spending without investments. */
    private fun List<Transaction>.sumOf(type: TxType) =
        filter { it.type == type && !isInvestment(it) }.sumOf { it.amountCents }

    private fun List<Transaction>.invested() = filter(::isInvestment).sumOf { it.amountCents }

    private fun categoryOf(t: Transaction) = t.category.trim().ifEmpty { UNCATEGORISED }

    private fun categories(current: List<Transaction>, previous: List<Transaction>?, type: TxType): List<CategoryTotal> {
        val entries = current.filter { it.type == type && !isInvestment(it) }
        val total = entries.sumOf { it.amountCents }
        if (total == 0L) return emptyList()
        val before = previous?.filter { it.type == type && !isInvestment(it) }?.groupBy(::categoryOf)
        return entries.groupBy(::categoryOf).map { (category, list) ->
            val cents = list.sumOf { it.amountCents }
            CategoryTotal(
                category = category,
                cents = cents,
                share = cents.toFloat() / total,
                count = list.size,
                previousCents = before?.let { b -> b[category].orEmpty().sumOf { it.amountCents } },
                topPayees = list.groupBy { it.party.trim().ifEmpty { "—" } }
                    .map { (party, rows) -> party to rows.sumOf { it.amountCents } }
                    .sortedByDescending { it.second }
                    .take(TOP_PAYEES),
            )
        }.sortedByDescending { it.cents }
    }

    /** Change from [previous] to [current] as a fraction, or null when there is nothing to compare. */
    fun change(current: Long, previous: Long?): Float? =
        if (previous == null || previous == 0L) null else (current - previous).toFloat() / previous
}
