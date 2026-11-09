package com.billbook.app.ui.statement

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.KeyboardType
import com.billbook.app.data.date
import com.billbook.app.export.ConversionMethod
import com.billbook.app.rates.RateFetcher
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billbook.app.BillBookApp
import com.billbook.app.data.TxType
import com.billbook.app.export.ExportFormat
import com.billbook.app.export.StatementData
import com.billbook.app.ui.components.DateField
import com.billbook.app.ui.theme.MoneyColors
import com.billbook.app.util.Money
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementScreen(
    onBack: () -> Unit,
    vm: StatementViewModel = viewModel(factory = StatementViewModel.Factory),
) {
    val period by vm.period.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val transactions by vm.transactions.collectAsStateWithLifecycle()
    val choice by vm.currencyChoice.collectAsStateWithLifecycle()
    val data by vm.statement.collectAsStateWithLifecycle()
    val rateStatus by vm.rateStatus.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val profileId by vm.profileId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = (context.applicationContext as BillBookApp).settings
    val defaultCurrency = settings.currency
    val currency = choice.report
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var askOnlineRates by remember { mutableStateOf(false) }
    var manualRate by remember { mutableStateOf(false) }

    val currenciesInPeriod = remember(transactions, defaultCurrency, choice) {
        (listOf(defaultCurrency) +
            (choice.currencies + transactions.map { it.currency.ifBlank { defaultCurrency } }).sorted()).distinct()
    }

    // Entries in other currencies without a rate: download rates (asking the first time).
    val missingCurrencies = data.unconverted.map { it.currency.ifBlank { defaultCurrency } }.distinct().sorted()
    LaunchedEffect(data.unconverted.map { it.id }, currency) {
        if (data.unconverted.isNotEmpty()) {
            when (settings.onlineRates) {
                true -> vm.downloadRates()
                null -> askOnlineRates = true
                false -> Unit
            }
        }
    }
    fun downloadRates() {
        if (settings.onlineRates == true) vm.downloadRates() else askOnlineRates = true
    }

    fun runExport(successMessage: String?, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            val error = try {
                block()
                null
            } catch (e: Exception) {
                e.message ?: "unknown error"
            }
            busy = false
            when {
                error != null -> snackbar.showSnackbar("Export failed: $error")
                successMessage != null -> snackbar.showSnackbar(successMessage)
            }
        }
    }

    /** The generated file being previewed; Save and Share use exactly this file. */
    var preview by remember { mutableStateOf<Pair<File, ExportFormat>?>(null) }

    fun openPreview(format: ExportFormat) = runExport(null) {
        preview = vm.preparePreview(format) to format
    }

    fun savedMessage(format: ExportFormat) = "${format.name} statement saved"
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDF.mime)) { uri ->
        val file = preview?.first
        if (uri != null && file != null) runExport(savedMessage(ExportFormat.PDF)) { vm.saveCopy(file, uri) }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime)) { uri ->
        val file = preview?.first
        if (uri != null && file != null) runExport(savedMessage(ExportFormat.CSV)) { vm.saveCopy(file, uri) }
    }

    fun share(file: File, format: ExportFormat) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, vm.shareUri(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, "Share statement"))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("No app available to share the file") }
        }
    }

    fun download(file: File, format: ExportFormat) {
        try {
            when (format) {
                ExportFormat.PDF -> savePdf.launch(file.name)
                ExportFormat.CSV -> saveCsv.launch(file.name)
            }
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("No file manager available to save the file") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statement") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (profiles.size > 1) {
                Text("Profile", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    profiles.forEach { p ->
                        FilterChip(
                            selected = p.id == profileId,
                            onClick = { vm.selectProfile(p.id) },
                            label = { Text(p.name) },
                        )
                    }
                }
            }

            Text("Period", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Period.entries.forEach { p ->
                    FilterChip(selected = period == p, onClick = { vm.selectPeriod(p) }, label = { Text(p.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("From", range.from, vm::setFrom, Modifier.weight(1f))
                DateField("To", range.to, vm::setTo, Modifier.weight(1f))
            }

            if (currenciesInPeriod.size > 1) {
                Text("Currencies", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = choice.currencies.containsAll(currenciesInPeriod),
                        onClick = { vm.selectCurrencies(currenciesInPeriod) },
                        label = { Text("All") },
                    )
                    currenciesInPeriod.forEach { code ->
                        FilterChip(
                            selected = code in choice.currencies,
                            onClick = { vm.toggleCurrency(code) },
                            label = { Text(code) },
                        )
                    }
                }
                if (choice.combined) {
                    Text("Report in", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        choice.currencies.sorted().forEach { code ->
                            FilterChip(
                                selected = code == choice.report,
                                onClick = { vm.selectReportCurrency(code) },
                                label = { Text(code) },
                            )
                        }
                    }
                    Text(
                        "Other currencies are converted into ${choice.report}: with the amount actually paid " +
                            "when it is recorded on the entry, otherwise with the exchange rate on the bill date.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ExchangeRateCard(
                        data = data,
                        missingCurrencies = missingCurrencies,
                        status = rateStatus,
                        onDownload = ::downloadRates,
                        onEnterRate = { manualRate = true },
                    )
                } else {
                    Text(
                        "Select more than one currency to combine them into one statement.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (choice.combined) "Summary (in $currency, ${choice.currencies.sorted().joinToString(" + ")})"
                        else "Summary ($currency)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SummaryLine("Entries", data.transactions.size.toString())
                    SummaryLine("Total income", Money.format(data.totalIncome, currency), MoneyColors.income)
                    SummaryLine("Total expenses", Money.format(data.totalExpense, currency), MoneyColors.expense)
                    SummaryLine("Tax paid on expenses", Money.format(data.taxOnExpenses, currency))
                    HorizontalDivider()
                    SummaryLine(
                        if (data.net >= 0) "Net surplus" else "Net deficit",
                        Money.format(data.net, currency),
                        if (data.net >= 0) MoneyColors.income else MoneyColors.expense,
                        bold = true,
                    )
                }
            }

            val expenseCategories = data.byCategory(TxType.EXPENSE)
            if (expenseCategories.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Expenses by category", style = MaterialTheme.typography.titleMedium)
                        expenseCategories.forEach { (category, amount) ->
                            SummaryLine(category, Money.format(amount, currency))
                        }
                    }
                }
            }

            Text("Statement file", style = MaterialTheme.typography.titleSmall)
            Text(
                "Preview the statement, then save or share it from the preview.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (busy && preview == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { openPreview(ExportFormat.PDF) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Visibility, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Preview PDF")
                }
                OutlinedButton(onClick = { openPreview(ExportFormat.CSV) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Visibility, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Preview CSV")
                }
            }
        }
    }

    if (askOnlineRates) {
        AlertDialog(
            onDismissRequest = { askOnlineRates = false },
            icon = { Icon(Icons.Default.CurrencyExchange, contentDescription = null) },
            title = { Text("Download exchange rates?") },
            text = {
                Text(
                    "To combine currencies, Pocket Ledger can download daily exchange rates from a free " +
                        "public service. Only currency codes and dates are sent, never your entries. " +
                        "Rates are kept on your phone. You can change this in Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askOnlineRates = false
                    settings.updateOnlineRates(true)
                    vm.downloadRates()
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askOnlineRates = false
                    settings.updateOnlineRates(false)
                }) { Text("No, I will enter rates") }
            },
        )
    }

    if (manualRate) {
        ManualRateDialog(
            report = currency,
            currencies = missingCurrencies.ifEmpty { choice.currencies.filter { it != currency }.sorted() },
            onDismiss = { manualRate = false },
            onSave = { quote, rate ->
                manualRate = false
                vm.setManualRate(quote, rate)
            },
        )
    }

    preview?.let { (file, format) ->
        StatementPreview(
            file = file,
            format = format,
            busy = busy,
            onClose = { preview = null },
            onDownload = { download(file, format) },
            onShare = { share(file, format) },
        )
    }
}

/** Shows how other currencies were converted and what is still missing an exchange rate. */
@Composable
private fun ExchangeRateCard(
    data: StatementData,
    missingCurrencies: List<String>,
    status: StatementViewModel.RateStatus,
    onDownload: () -> Unit,
    onEnterRate: () -> Unit,
) {
    val paid = data.conversions.values.count { it.method == ConversionMethod.PAID }
    val rated = data.conversions.size - paid
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Exchange rates", style = MaterialTheme.typography.titleMedium)
            if (data.conversions.isNotEmpty()) {
                Text(
                    listOfNotNull(
                        paid.takeIf { it > 0 }?.let { "$it with the amount actually paid" },
                        rated.takeIf { it > 0 }?.let { "$it with an exchange rate" },
                    ).joinToString(", ", prefix = "Converted: "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                rateLines(data).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (status.loading) {
                Text("Downloading exchange rates…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (data.unconverted.isNotEmpty() && !status.loading) {
                Text(
                    "${data.unconverted.size} ${if (data.unconverted.size == 1) "entry" else "entries"} in " +
                        "${missingCurrencies.joinToString(", ")} not included yet: no exchange rate.",
                    color = MoneyColors.expense,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (data.unconverted.any { it.date.isBefore(RateFetcher.FIRST_DAY) }) {
                    Text(
                        "Online rates start in March 2024. Enter a rate for older entries.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MoneyColors.expense) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDownload) { Text("Download rates") }
                    TextButton(onClick = onEnterRate) { Text("Enter a rate") }
                }
            } else if (data.unconverted.isEmpty() && data.conversions.isNotEmpty()) {
                TextButton(onClick = onEnterRate) { Text("Use my own rate instead") }
            }
        }
    }
}

/** "1 EUR = 369.94 LKR (28 Oct 2026)" lines for the rates in use, at most a few. */
private fun rateLines(data: StatementData): List<String> {
    val shortDate = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")
    val rates = data.conversions.values.filter { it.rate != null }
        .distinctBy { Triple(it.originalCurrency, it.rate, it.rateDay) }
    val lines = rates.take(4).map { c ->
        val day = c.rateDay?.let { " (${it.format(shortDate)})" } ?: " (your rate)"
        "1 ${data.currency} = ${"%.4f".format(c.rate)} ${c.originalCurrency}$day"
    }
    return if (rates.size > 4) lines + "…and ${rates.size - 4} more, listed in the PDF" else lines
}

@Composable
private fun ManualRateDialog(
    report: String,
    currencies: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, Double) -> Unit,
) {
    var quote by remember { mutableStateOf(currencies.firstOrNull().orEmpty()) }
    var text by remember { mutableStateOf("") }
    val rate = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter an exchange rate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currencies.size > 1) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        currencies.forEach { code ->
                            FilterChip(selected = code == quote, onClick = { quote = code }, label = { Text(code) })
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("1 $report = ? $quote") },
                    suffix = { Text(quote) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    "Used for $quote entries that have no paid amount or downloaded rate.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { rate?.let { onSave(quote, it) } }, enabled = rate != null && quote.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SummaryLine(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
