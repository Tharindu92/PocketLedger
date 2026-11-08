package com.billbook.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.billbook.app.BillBookApp
import com.billbook.app.data.Profile
import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.YearMonth

data class CurrencyTotals(val currency: String, val income: Long = 0, val expense: Long = 0) {
    val net: Long get() = income - expense
}

data class HomeState(
    val month: YearMonth = YearMonth.now(),
    val filter: TxType? = null,
    /** One entry per currency used this month — amounts in different currencies are never added together. */
    val totals: List<CurrencyTotals> = emptyList(),
    val items: List<Transaction> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val app: BillBookApp) : ViewModel() {
    private val dao = app.database.transactions()
    private val month = MutableStateFlow(YearMonth.now())
    private val filter = MutableStateFlow<TxType?>(null)

    val profiles: StateFlow<List<Profile>> = app.database.profiles().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Currently selected profile (falls back to the first one if it was deleted). */
    val activeProfile: StateFlow<Profile?> = combine(profiles, app.settings.activeProfileId) { list, id ->
        list.find { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val monthTransactions = combine(month, app.settings.activeProfileId) { m, p -> m to p }
        .flatMapLatest { (m, profileId) ->
            dao.observeBetween(profileId, m.atDay(1).toEpochDay(), m.atEndOfMonth().toEpochDay())
        }

    fun selectProfile(id: Long) = app.settings.setActiveProfile(id)

    val state: StateFlow<HomeState> = combine(month, filter, monthTransactions) { m, f, list ->
        HomeState(
            month = m,
            filter = f,
            totals = list.groupBy { it.currency }.map { (currency, entries) ->
                CurrencyTotals(
                    currency = currency,
                    income = entries.filter { it.type == TxType.INCOME }.sumOf { it.amountCents },
                    expense = entries.filter { it.type == TxType.EXPENSE }.sumOf { it.amountCents },
                )
            },
            items = if (f == null) list else list.filter { it.type == f },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /** Bank notification detections waiting for review. */
    val pendingDetections: StateFlow<Int> = app.database.detected().observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { it.plusMonths(1) }
    fun setFilter(type: TxType?) { filter.value = type }

    companion object {
        val Factory = viewModelFactory {
            initializer { HomeViewModel(this[APPLICATION_KEY] as BillBookApp) }
        }
    }
}
