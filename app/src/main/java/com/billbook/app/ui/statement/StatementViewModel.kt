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
import com.billbook.app.data.ExchangeRate
import com.billbook.app.data.Transaction
import com.billbook.app.data.date
import com.billbook.app.export.CurrencyConverter
import com.billbook.app.rates.ExchangeRateRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    // ------------------------------------------------------------ currencies

    /** Which currencies the statement covers and which one totals are shown in. */
    data class CurrencyChoice(val currencies: Set<String>, val report: String) {
        val combined: Boolean get() = currencies.size > 1
    }

    /** Chosen currencies; empty means just the default currency (a single-currency statement). */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    private val _report = MutableStateFlow<String?>(null)

    private fun resolve(selected: Set<String>, report: String?): CurrencyChoice {
        val default = app.settings.currency
        val currencies = selected.ifEmpty { setOf(default) }
        val reportIn = report?.takeIf { it in currencies }
            ?: default.takeIf { it in currencies }
            ?: currencies.sorted().first()
        return CurrencyChoice(currencies, reportIn)
    }

    val currencyChoice: StateFlow<CurrencyChoice> = combine(_selected, _report, ::resolve)
        .stateIn(viewModelScope, SharingStarted.Eagerly, resolve(emptySet(), null))

    /** Adds or removes a currency; the last one cannot be removed. */
    fun toggleCurrency(code: String) {
        val current = currencyChoice.value.currencies
        _selected.value = if (code in current) (current - code).ifEmpty { current } else current + code
    }

    fun selectCurrencies(codes: Collection<String>) {
        if (codes.isNotEmpty()) _selected.value = codes.toSet()
    }

    fun selectReportCurrency(code: String) { _report.value = code }

    private val rateDao = app.database.rates()
    private val rateRepository = ExchangeRateRepository(rateDao)

    private val rates = currencyChoice.map { it.report }.distinctUntilChanged()
        .flatMapLatest { rateDao.observeForBase(it) }

    /** The statement as it will be exported, with other currencies converted into the report currency. */
    val statement: StateFlow<StatementData> = combine(transactions, currencyChoice, rates, _range, profiles) {
            items, choice, rateList, range, _ ->
        assemble(range, items, choice, rateList)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        StatementData(_range.value.from, _range.value.to, app.settings.currency, "", emptyList()),
    )

    private fun assemble(range: DateRange, items: List<Transaction>, choice: CurrencyChoice, rateList: List<ExchangeRate>) =
        CurrencyConverter.assemble(
            from = range.from,
            to = range.to,
            items = items,
            currencies = choice.currencies,
            report = choice.report,
            defaultCurrency = app.settings.currency,
            rates = rateList,
            businessName = app.settings.businessName,
            profileName = profileName(),
        )

    data class RateStatus(val loading: Boolean = false, val error: String? = null)

    private val _rateStatus = MutableStateFlow(RateStatus())
    val rateStatus: StateFlow<RateStatus> = _rateStatus.asStateFlow()

    /** Downloads exchange rates for the entries that could not be converted yet. */
    fun downloadRates() {
        val data = statement.value
        if (data.unconverted.isEmpty() || _rateStatus.value.loading) return
        val default = app.settings.currency
        val needed = data.unconverted.map { it.currency.ifBlank { default } to it.date }
        viewModelScope.launch {
            _rateStatus.value = RateStatus(loading = true)
            _rateStatus.value = try {
                rateRepository.download(data.currency, needed)
                RateStatus()
            } catch (e: IOException) {
                RateStatus(error = "Couldn't download exchange rates. Check the internet connection, or enter a rate.")
            }
        }
    }

    /** 1 [report currency] = [rate] [quote], used for entries without a downloaded rate. */
    fun setManualRate(quote: String, rate: Double) {
        val report = currencyChoice.value.report
        viewModelScope.launch { rateRepository.setManual(report, quote, rate) }
    }

    fun fileName(format: ExportFormat): String {
        val choice = currencyChoice.value
        val label = if (choice.combined) "${choice.report}_combined" else choice.report
        return _range.value.let { StatementExporter.fileName(profileName(), it.from, it.to, label, format) }
    }

    /** Fresh from the database, so the file matches what is saved even if the screen lags behind. */
    private suspend fun buildData(): StatementData {
        val r = _range.value
        val choice = currencyChoice.value
        val items = dao.listBetween(_profileId.value, r.from.toEpochDay(), r.to.toEpochDay())
        return assemble(r, items, choice, rateDao.forBase(choice.report))
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
