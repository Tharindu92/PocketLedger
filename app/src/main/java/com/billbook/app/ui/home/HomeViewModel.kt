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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import com.billbook.app.data.Categories
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

    fun selectProfile(id: Long) { clearSelection(); app.settings.setActiveProfile(id) }

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

    /** Entries ticked for deleting; empty when the list is not in selection mode. */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected

    fun toggleSelection(id: Long) = _selected.update { if (id in it) it - id else it + id }

    /** Ticks every entry currently shown, or unticks them all when they are already ticked. */
    fun toggleSelectAll() {
        val shown = state.value.items.map { it.id }.toSet()
        _selected.update { if (it.containsAll(shown)) emptySet() else shown }
    }

    fun clearSelection() { _selected.value = emptySet() }

    /** Deletes the ticked entries that are on screen, with their bill photos. */
    fun deleteSelected(onDone: (Int) -> Unit) {
        val doomed = selectedItems()
        _selected.value = emptySet()
        if (doomed.isEmpty()) return
        viewModelScope.launch {
            dao.deleteAll(doomed)
            withContext(Dispatchers.IO) { doomed.forEach { t -> t.imagePath?.let { File(it).delete() } } }
            onDone(doomed.size)
        }
    }

    /** Ticked entries that are on screen. */
    private fun selectedItems(): List<Transaction> = state.value.items.filter { it.id in _selected.value }

    /** Category names to offer per type: the built-in ones, then any custom ones used before. */
    val categories: StateFlow<Map<TxType, List<String>>> = dao.observeUsedCategories()
        .map { used ->
            TxType.entries.associateWith { type ->
                (Categories.defaultsFor(type) + used.filter { it.type == type }.map { it.category }).distinct()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Sets [category] on the ticked entries of [type]; ticked entries of the other type keep theirs. */
    fun setCategory(type: TxType, category: String, onDone: (Int) -> Unit) {
        val changed = selectedItems().filter { it.type == type }.map { it.copy(category = category) }
        if (changed.isEmpty()) return
        viewModelScope.launch {
            dao.upsertAll(changed)
            onDone(changed.size)
        }
    }

    /** Moves the ticked entries to another profile; they disappear from this list. */
    fun moveToProfile(profileId: Long, onDone: (Int) -> Unit) {
        val moved = selectedItems().filter { it.profileId != profileId }.map { it.copy(profileId = profileId) }
        _selected.value = emptySet()
        if (moved.isEmpty()) return
        viewModelScope.launch {
            dao.upsertAll(moved)
            onDone(moved.size)
        }
    }

    fun previousMonth() { clearSelection(); month.update { it.minusMonths(1) } }
    fun nextMonth() { clearSelection(); month.update { it.plusMonths(1) } }
    fun setFilter(type: TxType?) { clearSelection(); filter.value = type }

    companion object {
        val Factory = viewModelFactory {
            initializer { HomeViewModel(this[APPLICATION_KEY] as BillBookApp) }
        }
    }
}
