package com.billbook.app.ui.statement

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.billbook.app.BillBookApp
import com.billbook.app.data.Profile
import com.billbook.app.data.Transaction
import kotlinx.coroutines.flow.combine
import com.billbook.app.export.ExportFormat
import com.billbook.app.export.StatementData
import com.billbook.app.export.StatementExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth

enum class Period(val label: String) {
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    THIS_YEAR("This year"),
    LAST_YEAR("Last year"),
    CUSTOM("Custom"),
}

data class DateRange(val from: LocalDate, val to: LocalDate)

@OptIn(ExperimentalCoroutinesApi::class)
class StatementViewModel(private val app: BillBookApp) : ViewModel() {
    private val dao = app.database.transactions()

    private val _period = MutableStateFlow(Period.THIS_MONTH)
    val period: StateFlow<Period> = _period.asStateFlow()

    private val _range = MutableStateFlow(rangeFor(Period.THIS_MONTH))
    val range: StateFlow<DateRange> = _range.asStateFlow()

    val profiles: StateFlow<List<Profile>> = app.database.profiles().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Statements are per profile; starts on the profile shown on the home screen. */
    private val _profileId = MutableStateFlow(app.settings.activeProfileId.value)
    val profileId: StateFlow<Long> = _profileId.asStateFlow()

    fun selectProfile(id: Long) { _profileId.value = id }

    val transactions: StateFlow<List<Transaction>> = combine(_range, _profileId) { r, p -> r to p }
        .flatMapLatest { (r, p) -> dao.observeBetween(p, r.from.toEpochDay(), r.to.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun profileName(): String =
        profiles.value.find { it.id == _profileId.value }?.name.orEmpty()

    /** Statement currency; null means the default currency from settings. */
    private val _currency = MutableStateFlow<String?>(null)
    val currency: StateFlow<String?> = _currency.asStateFlow()

    fun selectCurrency(code: String) { _currency.value = code }

    private fun effectiveCurrency() = _currency.value ?: app.settings.currency

    fun selectPeriod(period: Period) {
        _period.value = period
        if (period != Period.CUSTOM) _range.value = rangeFor(period)
    }

    fun setFrom(date: LocalDate) {
        _period.value = Period.CUSTOM
        _range.update { DateRange(date, maxOf(date, it.to)) }
    }

    fun setTo(date: LocalDate) {
        _period.value = Period.CUSTOM
        _range.update { DateRange(minOf(it.from, date), date) }
    }

    fun fileName(format: ExportFormat): String =
        _range.value.let { StatementExporter.fileName(profileName(), it.from, it.to, effectiveCurrency(), format) }

    /** Only entries in the selected currency, so totals are never mixed across currencies. */
    private suspend fun buildData(): StatementData {
        val r = _range.value
        val currency = effectiveCurrency()
        val default = app.settings.currency
        val profileId = _profileId.value
        val profileName = app.database.profiles().get(profileId)?.name.orEmpty()
        val items = dao.listBetween(profileId, r.from.toEpochDay(), r.to.toEpochDay())
            .filter { it.currency.ifBlank { default } == currency }
        return StatementData(r.from, r.to, currency, app.settings.businessName, items, profileName)
    }

    /** Writes the statement to cache for previewing; Save and Share then use this same file. */
    suspend fun preparePreview(format: ExportFormat): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        // Only the latest preview is kept.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(format))
        val data = buildData()
        file.outputStream().use { StatementExporter.write(data, format, it) }
        file
    }

    /** A content:// Uri other apps can read, for sharing a previewed file. */
    fun shareUri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)

    /** Copies a previewed file to a location the user picked (Downloads, Drive, …). */
    suspend fun saveCopy(file: File, uri: Uri) = withContext(Dispatchers.IO) {
        val out = app.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write to the selected file")
        out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    }

    companion object {
        fun rangeFor(period: Period, today: LocalDate = LocalDate.now()): DateRange {
            val month = YearMonth.from(today)
            return when (period) {
                Period.THIS_MONTH, Period.CUSTOM -> DateRange(month.atDay(1), month.atEndOfMonth())
                Period.LAST_MONTH -> month.minusMonths(1).let { DateRange(it.atDay(1), it.atEndOfMonth()) }
                Period.THIS_YEAR -> DateRange(today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31))
                Period.LAST_YEAR -> today.minusYears(1).let {
                    DateRange(it.withDayOfYear(1), it.withMonth(12).withDayOfMonth(31))
                }
            }
        }

        val Factory = viewModelFactory {
            initializer { StatementViewModel(this[APPLICATION_KEY] as BillBookApp) }
        }
    }
}
