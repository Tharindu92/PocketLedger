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
}

/** Which entries are analysed. [profileId] null means all profiles together. */
data class InsightsFilter(
    val period: Period = Period.SIX_MONTHS,
    val profileId: Long? = null,
    val currency: String = "",
)

data class InsightsState(
    val filter: InsightsFilter,
    val from: YearMonth,
    val to: YearMonth,
    val data: InsightsData?,
    /** Currencies used by entries in the period, analysis currency first. */
    val currencies: List<String>,
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

    fun setPeriod(p: Period) { filter.value = filter.value.copy(period = p) }
    fun setProfile(id: Long?) { filter.value = filter.value.copy(profileId = id) }
    fun setCurrency(code: String) { filter.value = filter.value.copy(currency = code) }

    private fun entries(profileId: Long?, from: LocalDate, to: LocalDate): Flow<List<Transaction>> =
        if (profileId == null) dao.observeAllProfilesBetween(from.toEpochDay(), to.toEpochDay())
        else dao.observeBetween(profileId, from.toEpochDay(), to.toEpochDay())

    /** First and last month of the period, and the same-length period before (null for all time). */
    private data class Range(val from: YearMonth, val to: YearMonth, val previous: Pair<YearMonth, YearMonth>?)

    private fun range(period: Period, firstDay: Long?): Range {
        val now = YearMonth.now()
        val from = when (period) {
            Period.THIS_MONTH -> now
            Period.THREE_MONTHS -> now.minusMonths(2)
            Period.SIX_MONTHS -> now.minusMonths(5)
            Period.THIS_YEAR -> YearMonth.of(now.year, 1)
            Period.TWELVE_MONTHS -> now.minusMonths(11)
            Period.ALL_TIME -> firstDay?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }?.coerceAtMost(now) ?: now
        }
        val length = (now.year - from.year) * 12L + (now.monthValue - from.monthValue) + 1
        val previous = if (period == Period.ALL_TIME) null else from.minusMonths(length) to from.minusMonths(1)
        return Range(from, now, previous)
    }

    val state: StateFlow<InsightsState?> = filter.flatMapLatest { f ->
        dao.observeFirstDay(f.profileId).map { first -> f to range(f.period, first) }
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
