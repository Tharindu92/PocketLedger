package com.billbook.app.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.billbook.app.BillBookApp
import com.billbook.app.analysis.Insights
import com.billbook.app.analysis.InsightsData
import com.billbook.app.data.Profile
import com.billbook.app.data.Transaction
import com.billbook.app.data.date
import com.billbook.app.rates.ExchangeRateRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

enum class Period(val label: String) {
    THIS_MONTH("This month"),
    THREE_MONTHS("3 months"),
    SIX_MONTHS("6 months"),
    THIS_YEAR("This year"),
    TWELVE_MONTHS("12 months"),
    ALL_TIME("All time"),
    /** Months the user picked, or reached with the arrows. */
    CUSTOM("Choose…"),
}

/** Which entries are analysed. [profileId] null means all profiles together. */
data class InsightsFilter(
    val period: Period = Period.SIX_MONTHS,
    val profileId: Long? = null,
    val currency: String = "",
    /** First and last month for [Period.CUSTOM]. */
    val customFrom: YearMonth? = null,
    val customTo: YearMonth? = null,
    /** Steps taken with the arrows: years for [Period.THIS_YEAR], months otherwise; 0 = up to now. */
    val offset: Int = 0,
)

data class InsightsState(
    val filter: InsightsFilter,
    val from: YearMonth,
    val to: YearMonth,
    val data: InsightsData?,
    /** Currencies used by entries in the period, analysis currency first. */
    val currencies: List<String>,
    /** What the ▲▼ figures compare with, e.g. "the 3 months before" or "Jan – Nov 2025"; null for all time. */
    val comparedTo: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(private val app: BillBookApp) : ViewModel() {
    private val dao = app.database.transactions()
    private val rateDao = app.database.rates()
    private val rateRepository = ExchangeRateRepository(rateDao)

    val profiles: StateFlow<List<Profile>> = app.database.profiles().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filter = MutableStateFlow(
        InsightsFilter(profileId = app.settings.activeProfileId.value, currency = app.settings.currency)
    )

    fun setPeriod(p: Period) { filter.value = filter.value.copy(period = p, offset = 0) }

    /** [from]..[to] chosen in the month picker; one month when they are equal. */
    fun setMonths(from: YearMonth, to: YearMonth) {
        val (a, b) = if (from <= to) from to to else to to from
        filter.value = filter.value.copy(period = Period.CUSTOM, customFrom = a, customTo = b)
    }

    /**
     * Arrows: "This year" moves a whole year (Jan – Dec 2025 ← Jan – Nov 2026); every other period
     * slides by one month and keeps its length (Aug – Oct ← Sep – Nov).
     */
    fun shift(direction: Int) {
        val f = filter.value
        filter.value = when (f.period) {
            Period.ALL_TIME -> f
            Period.CUSTOM -> f.copy(
                customFrom = f.customFrom?.plusMonths(direction.toLong()),
                customTo = f.customTo?.plusMonths(direction.toLong()),
            )
            else -> f.copy(offset = minOf(0, f.offset + direction))
        }
    }
    fun setProfile(id: Long?) { filter.value = filter.value.copy(profileId = id) }
    fun setCurrency(code: String) { filter.value = filter.value.copy(currency = code) }

    private fun entries(profileId: Long?, from: LocalDate, to: LocalDate): Flow<List<Transaction>> =
        if (profileId == null) dao.observeAllProfilesBetween(from.toEpochDay(), to.toEpochDay())
        else dao.observeBetween(profileId, from.toEpochDay(), to.toEpochDay())

    /** First and last month of the period, and the same-length period before (null for all time). */
    private data class Range(
        val from: YearMonth,
        val to: YearMonth,
        val previous: Pair<YearMonth, YearMonth>?,
        val comparedTo: String?,
    )

    /** The same number of months just before [from]..[to]. */
    private fun before(from: YearMonth, to: YearMonth): Pair<Pair<YearMonth, YearMonth>, String> {
        val length = monthsBetween(from, to)
        return (from.minusMonths(length) to from.minusMonths(1)) to
            if (length == 1L) "the month before" else "the $length months before"
    }

    private fun monthsBetween(from: YearMonth, to: YearMonth) =
        (to.year - from.year) * 12L + (to.monthValue - from.monthValue) + 1

    private fun range(f: InsightsFilter, firstDay: Long?): Range {
        val period = f.period
        val now = YearMonth.now()
        fun rolling(months: Long): Range {
            val to = now.plusMonths(f.offset.toLong())
            val from = to.minusMonths(months - 1)
            val (prev, label) = before(from, to)
            return Range(from, to, prev, label)
        }
        return when (period) {
            Period.CUSTOM -> {
                val from = f.customFrom ?: now
                val to = f.customTo ?: now
                val (prev, label) = before(from, to)
                Range(from, to, prev, label)
            }
            Period.THIS_MONTH -> rolling(1)
            Period.THREE_MONTHS -> rolling(3)
            Period.SIX_MONTHS -> rolling(6)
            Period.TWELVE_MONTHS -> rolling(12)
            Period.THIS_YEAR -> {
                // A past year is shown in full; the current year up to this month.
                val year = now.year + f.offset
                val from = YearMonth.of(year, 1)
                val to = if (year == now.year) now else YearMonth.of(year, 12)
                // Compared with the same months a year earlier, e.g. Jan – Nov 2026 with Jan – Nov 2025.
                val prev = from.minusYears(1) to to.minusYears(1)
                Range(from, to, prev, if (to.monthValue == 12) "${year - 1}" else "the same months of ${year - 1}")
            }
            Period.ALL_TIME -> {
                val from = firstDay?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }?.coerceAtMost(now) ?: now
                Range(from, now, null, null)
            }
        }
    }

    val state: StateFlow<InsightsState?> = filter.flatMapLatest { f ->
        dao.observeFirstDay(f.profileId).map { first -> f to range(f, first) }
    }.flatMapLatest { (f, r) ->
        val current = entries(f.profileId, r.from.atDay(1), r.to.atEndOfMonth())
        val previous = r.previous?.let { (a, b) -> entries(f.profileId, a.atDay(1), b.atEndOfMonth()) } ?: flowOf(null)
        combine(current, previous, rateDao.observeForBase(f.currency)) { items, before, rates ->
            val default = app.settings.currency
            InsightsState(
                filter = f,
                from = r.from,
                to = r.to,
                data = Insights.build(r.from, r.to, items, before, f.currency, default, rates),
                currencies = (listOf(f.currency, default) + items.map { it.currency.ifBlank { default } })
                    .distinct(),
                comparedTo = r.comparedTo,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Online rates may be used (the user agreed in Settings or on a statement). */
    val canDownloadRates: Boolean get() = app.settings.onlineRates == true

    var rateMessage = MutableStateFlow<String?>(null)
        private set

    fun downloadRates() {
        val s = state.value ?: return
        val missing = s.data?.unconverted.orEmpty()
        if (missing.isEmpty()) return
        val default = app.settings.currency
        viewModelScope.launch {
            rateMessage.value = "Downloading exchange rates…"
            rateMessage.value = try {
                rateRepository.download(s.filter.currency, missing.map { it.currency.ifBlank { default } to it.date })
                null
            } catch (e: IOException) {
                "Couldn't download exchange rates. Check the internet connection."
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { InsightsViewModel(this[APPLICATION_KEY] as BillBookApp) }
        }
    }
}
