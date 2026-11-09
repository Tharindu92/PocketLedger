package com.billbook.app.ui.importer

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.billbook.app.BillBookApp
import com.billbook.app.data.Profile
import com.billbook.app.data.TxType
import com.billbook.app.importer.BankCsvParser
import com.billbook.app.importer.ImportMapping
import com.billbook.app.importer.StatementLine
import com.billbook.app.importer.StatementParseResult
import com.billbook.app.importer.StatementReader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.billbook.app.data.Categories
import kotlinx.coroutines.launch
import java.io.File

/** One booking on the review screen: whether to import it, into which profile, as income or expense. */
data class ImportItem(
    val line: StatementLine,
    val selected: Boolean,
    val profileId: Long,
    val type: TxType,
    /** Guessed from the booking text at first; blank when nothing fitted. */
    val category: String,
    /** An entry with the same amount and date is already saved; unticked by default. */
    val duplicate: Boolean,
)

class ImportViewModel(private val app: BillBookApp, private val uri: Uri) : ViewModel() {

    sealed interface Stage {
        data class Reading(val message: String) : Stage
        data class Failed(val message: String) : Stage
        /** CSV columns not recognised: the user picks them from [header]. */
        data class ChooseColumns(val header: List<String>) : Stage
        data object Review : Stage
        data class Done(val count: Int) : Stage
    }

    private val reader = StatementReader(app)
    private var file: File? = null

    var stage by mutableStateOf<Stage>(Stage.Reading("Reading statement…"))
        private set
    var items by mutableStateOf<List<ImportItem>>(emptyList())
        private set
    var result by mutableStateOf<StatementParseResult?>(null)
        private set

    val profiles: StateFlow<List<Profile>> = app.database.profiles().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            try {
                val (f, kind) = reader.open(uri)
                file = f
                val parsed = when (kind) {
                    StatementReader.Kind.CSV -> reader.readCsv(f, app.settings.currency)
                    StatementReader.Kind.PDF -> reader.readPdf(f, app.settings.currency) { page, pages ->
                        stage = Stage.Reading("Reading page $page of $pages…")
                    }
                }
                show(parsed)
            } catch (e: Exception) {
                stage = Stage.Failed("Couldn't read this file (${e.message ?: "unknown error"}).")
            }
        }
    }

    /** Uses the columns the user picked for a CSV whose header was not recognised. */
    fun useColumns(columns: BankCsvParser.Columns) {
        val f = file ?: return
        stage = Stage.Reading("Reading statement…")
        viewModelScope.launch {
            show(reader.readCsv(f, app.settings.currency, columns))
        }
    }

    private suspend fun show(parsed: StatementParseResult) {
        result = parsed
        parsed.unrecognisedHeader?.let {
            stage = Stage.ChooseColumns(it)
            return
        }
        if (parsed.lines.isEmpty()) {
            stage = Stage.Failed("No bookings found in this statement.")
            return
        }
        val from = parsed.lines.minOf { it.date }.toEpochDay() - ImportMapping.DAYS
        val to = parsed.lines.maxOf { it.date }.toEpochDay() + ImportMapping.DAYS
        val existing = app.database.transactions().allBetween(from, to)
        val profile = app.settings.activeProfileId.value
        items = parsed.lines.sortedByDescending { it.date }.map { line ->
            val duplicate = ImportMapping.isDuplicate(line, existing)
            val type = if (line.amountCents > 0) TxType.INCOME else TxType.EXPENSE
            ImportItem(
                line = line,
                selected = !duplicate,
                profileId = profile,
                type = type,
                category = ImportMapping.guessCategory(line, type),
                duplicate = duplicate,
            )
        }
        stage = Stage.Review
    }

    fun toggle(index: Int) = edit(index) { it.copy(selected = !it.selected) }
    fun setProfile(index: Int, profileId: Long) = edit(index) { it.copy(profileId = profileId, selected = true) }
    /** Switching income/expense also resets the category: the two have different lists. */
    fun toggleType(index: Int) = edit(index) {
        val type = if (it.type == TxType.INCOME) TxType.EXPENSE else TxType.INCOME
        it.copy(type = type, category = ImportMapping.guessCategory(it.line, type))
    }

    fun setCategory(index: Int, category: String) = edit(index) { it.copy(category = category, selected = true) }

    /** Category names per type: built-in ones, then custom ones used before. */
    val categories: StateFlow<Map<TxType, List<String>>> = app.database.transactions().observeUsedCategories()
        .map { used ->
            TxType.entries.associateWith { type ->
                (Categories.defaultsFor(type) + used.filter { it.type == type }.map { it.category }).distinct()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun selectAll(selected: Boolean) {
        items = items.map { it.copy(selected = selected) }
    }

    /** Quick option: every ticked booking goes to [profileId]. */
    fun setProfileForSelected(profileId: Long) {
        items = items.map { if (it.selected) it.copy(profileId = profileId) else it }
    }

    private fun edit(index: Int, change: (ImportItem) -> ImportItem) {
        items = items.toMutableList().also { it[index] = change(it[index]) }
    }

    fun import() {
        val chosen = items.filter { it.selected }
        if (chosen.isEmpty() || stage !is Stage.Review) return
        stage = Stage.Reading("Saving…")
        viewModelScope.launch {
            app.database.transactions().upsertAll(
                chosen.map { ImportMapping.toTransaction(it.line, it.type, it.profileId, it.category) }
            )
            stage = Stage.Done(chosen.size)
        }
    }

    override fun onCleared() {
        file?.delete()
    }

    companion object {
        fun factory(uri: Uri) = viewModelFactory {
            initializer { ImportViewModel(this[APPLICATION_KEY] as BillBookApp, uri) }
        }
    }
}
