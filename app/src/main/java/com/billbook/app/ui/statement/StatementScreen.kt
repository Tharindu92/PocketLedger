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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.TextButton
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

    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDF.mime)) { uri ->
        if (uri != null) runExport("PDF statement saved") { vm.exportTo(uri, ExportFormat.PDF) }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime)) { uri ->
        if (uri != null) runExport("CSV statement saved") { vm.exportTo(uri, ExportFormat.CSV) }
    }

    fun share(format: ExportFormat) = runExport(null) {
        val uri = vm.exportForShare(format)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share statement"))
    }

    fun download(format: ExportFormat) {
        try {
            when (format) {
                ExportFormat.PDF -> savePdf.launch(vm.fileName(format))
                ExportFormat.CSV -> saveCsv.launch(vm.fileName(format))
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

            Text("Download statement", style = MaterialTheme.typography.titleSmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { download(ExportFormat.PDF) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("PDF")
                }
                OutlinedButton(onClick = { download(ExportFormat.CSV) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("CSV (Excel)")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { share(ExportFormat.PDF) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share PDF")
                }
                TextButton(onClick = { share(ExportFormat.CSV) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share CSV")
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
