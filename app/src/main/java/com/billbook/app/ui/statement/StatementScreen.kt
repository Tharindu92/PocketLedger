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
import androidx.compose.material.icons.filled.Visibility
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
    val selectedCurrency by vm.currency.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val profileId by vm.profileId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultCurrency = (context.applicationContext as BillBookApp).settings.currency
    val currency = selectedCurrency ?: defaultCurrency
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }

    val currenciesInPeriod = remember(transactions, defaultCurrency, currency) {
        (listOf(defaultCurrency, currency) + transactions.map { it.currency.ifBlank { defaultCurrency } }.sorted())
            .distinct()
    }
    val data = remember(transactions, range, currency, defaultCurrency) {
        StatementData(
            range.from, range.to, currency, "",
            transactions.filter { it.currency.ifBlank { defaultCurrency } == currency },
        )
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
                Text("Currency", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    currenciesInPeriod.forEach { code ->
                        FilterChip(
                            selected = currency == code,
                            onClick = { vm.selectCurrency(code) },
                            label = { Text(code) },
                        )
                    }
                }
                Text(
                    "This period has entries in more than one currency. Each statement covers one currency.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Summary ($currency)", style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun SummaryLine(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
