package com.billbook.app.ui.importer

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import com.billbook.app.ui.components.RememberRuleCard
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billbook.app.data.Profile
import com.billbook.app.data.TxType
import com.billbook.app.importer.BankCsvParser
import com.billbook.app.importer.BankPdfParser
import com.billbook.app.ui.components.DisplayDate
import com.billbook.app.ui.theme.MoneyColors
import com.billbook.app.util.Money
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(uri: Uri, onDone: () -> Unit) {
    val vm: ImportViewModel = viewModel(factory = ImportViewModel.factory(uri))
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val stage = vm.stage

    LaunchedEffect(stage) {
        if (stage is ImportViewModel.Stage.Done) {
            val n = stage.count
            Toast.makeText(context, "Imported $n ${if (n == 1) "entry" else "entries"}", Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Import statement")
                        vm.result?.takeIf { stage is ImportViewModel.Stage.Review }?.let { r ->
                            Text(
                                "${r.source} · ${r.lines.size} bookings",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            if (stage is ImportViewModel.Stage.Review) {
                val count = vm.items.count { it.selected }
                val notice = vm.notice
                LaunchedEffect(notice) {
                    if (notice != null) {
                        delay(4_000)
                        vm.clearNotice()
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                        vm.offer?.let { offer ->
                            RememberRuleCard(offer, onRemember = vm::remember, onDismiss = vm::dismissOffer)
                        }
                        if (notice != null) {
                            Text(notice, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = vm::import,
                            enabled = count > 0,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                        ) {
                            Text(if (count == 0) "Select bookings to import" else "Import $count ${if (count == 1) "entry" else "entries"}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (stage) {
                is ImportViewModel.Stage.Reading, is ImportViewModel.Stage.Done -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text((stage as? ImportViewModel.Stage.Reading)?.message ?: "Done")
                }
                is ImportViewModel.Stage.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stage.message)
                    Text(
                        "Most banks offer a CSV download in their app or online banking. It gives the most reliable result.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = onDone) { Text("Back") }
                }
                is ImportViewModel.Stage.ChooseColumns -> ColumnPicker(stage.header, vm::useColumns)
                ImportViewModel.Stage.Review -> ReviewList(vm, profiles)
            }
        }
    }
}

@Composable
private fun ReviewList(vm: ImportViewModel, profiles: List<Profile>) {
    val items = vm.items
    val result = vm.result
    val categories by vm.categories.collectAsStateWithLifecycle()
    var pickCategory by remember { mutableStateOf(false) }
    if (pickCategory) {
        BulkCategoryDialog(
            ticked = items.filter { it.selected },
            categories = categories,
            onPick = vm::setCategoryForSelected,
            onDismiss = { pickCategory = false },
        )
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (result?.source) {
                    BankPdfParser.SOURCE_OCR -> Notice(
                        "This PDF is a scan, so it was read with text recognition. Please check dates, amounts " +
                            "and income/expense before importing. Rows marked “check” were partly guessed."
                    )
                    BankPdfParser.SOURCE_TEXT -> Notice(
                        "Read from the PDF's text. Each bank lays out statements differently, so please check " +
                            "the rows marked “check” (income/expense or date was guessed)."
                    )
                }
                val duplicates = items.count { it.duplicate }
                if (duplicates > 0) {
                    Notice(
                        "$duplicates ${if (duplicates == 1) "booking looks" else "bookings look"} already recorded " +
                            "(same amount within ${3} days) and ${if (duplicates == 1) "is" else "are"} unticked."
                    )
                }
                result?.skipped?.takeIf { it > 0 }?.let {
                    Text("$it rows could not be read and were left out.", style = MaterialTheme.typography.bodySmall)
                }
                if (profiles.size > 1) {
                    Text("Put all ticked bookings in", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val ticked = items.filter { it.selected }
                        profiles.forEach { p ->
                            FilterChip(
                                selected = ticked.isNotEmpty() && ticked.all { it.profileId == p.id },
                                onClick = { vm.setProfileForSelected(p.id) },
                                label = { Text(p.name) },
                            )
                        }
                    }
                    Text(
                        "Or choose a profile on each booking below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val ticked = items.filter { it.selected }
                OutlinedButton(onClick = { pickCategory = true }, enabled = ticked.isNotEmpty()) {
                    Icon(Icons.Default.Category, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Set category for ticked bookings")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${items.count { it.selected }} of ${items.size} ticked",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.selectAll(true) }) { Text("Select all") }
                    TextButton(onClick = { vm.selectAll(false) }) { Text("None") }
                }
            }
            HorizontalDivider()
        }
        itemsIndexed(items) { index, item ->
            ImportRow(
                item = item,
                profiles = profiles,
                categories = categories[item.type].orEmpty(),
                onToggle = { vm.toggle(index) },
                onProfile = { vm.setProfile(index, it) },
                onType = { vm.toggleType(index) },
                onCategory = { vm.setCategory(index, it) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImportRow(
    item: ImportItem,
    profiles: List<Profile>,
    categories: List<String>,
    onToggle: () -> Unit,
    onProfile: (Long) -> Unit,
    onType: () -> Unit,
    onCategory: (String) -> Unit,
) {
    val line = item.line
    val income = item.type == TxType.INCOME
    Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = item.selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    line.party.ifBlank { "—" },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    (if (income) "+" else "−") + Money.format(abs(line.amountCents), line.currency),
                    color = if (income) MoneyColors.income else MoneyColors.expense,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row {
                Text(
                    listOf(line.date.format(DisplayDate), line.reference.ifBlank { line.kind })
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (line.originalAmountCents != null && line.originalCurrency != null) {
                    Text(
                        Money.format(line.originalAmountCents, line.originalCurrency),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (profiles.size > 1) ProfilePicker(item.profileId, profiles, onProfile)
                AssistChip(onClick = onType, label = { Text(if (income) "Income" else "Expense") })
                CategoryPicker(item.category, categories, onCategory)
                if (item.duplicate) {
                    Text("Already recorded?", style = MaterialTheme.typography.labelSmall, color = MoneyColors.expense)
                }
                if (line.uncertain) {
                    Text("check", style = MaterialTheme.typography.labelSmall, color = MoneyColors.expense)
                }
            }
        }
    }
}

@Composable
private fun ProfilePicker(selectedId: Long, profiles: List<Profile>, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(profiles.find { it.id == selectedId }?.name ?: "Profile") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; onPick(p.id) })
            }
        }
    }
}

/** Categories for all ticked bookings; expense and income have separate lists, shown only for the types ticked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkCategoryDialog(
    ticked: List<ImportItem>,
    categories: Map<TxType, List<String>>,
    onPick: (TxType, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val types = TxType.entries.filter { type -> ticked.any { it.type == type } }
    fun entries(n: Int) = if (n == 1) "1 booking" else "$n bookings"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category for ${entries(ticked.size)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                types.forEach { type ->
                    val ofType = ticked.filter { it.type == type }
                    val current = ofType.map { it.category }.distinct().singleOrNull()
                    Text(
                        (if (type == TxType.INCOME) "Income" else "Expenses") +
                            if (types.size > 1) " (${entries(ofType.size)})" else "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories[type].orEmpty().forEach { name ->
                            FilterChip(
                                selected = name == current,
                                onClick = {
                                    onPick(type, name)
                                    if (types.size == 1) onDismiss()
                                },
                                label = { Text(name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun CategoryPicker(selected: String, categories: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = {
                Text(
                    selected.ifBlank { "Category" },
                    color = if (selected.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    maxLines = 1,
                )
            },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (selected.isNotBlank()) {
                DropdownMenuItem(text = { Text("No category") }, onClick = { open = false; onPick("") })
                HorizontalDivider()
            }
            categories.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, fontWeight = if (name == selected) FontWeight.SemiBold else null) },
                    onClick = { open = false; onPick(name) },
                )
            }
        }
    }
}

/** For CSV files whose columns were not recognised: the user points at date, amount and description. */
@Composable
private fun ColumnPicker(header: List<String>, onContinue: (BankCsvParser.Columns) -> Unit) {
    var date by remember { mutableStateOf<Int?>(null) }
    var amount by remember { mutableStateOf<Int?>(null) }
    var party by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Which columns hold what?", style = MaterialTheme.typography.titleMedium)
        Text(
            "This bank's file wasn't recognised automatically. Pick the columns once below.",
            style = MaterialTheme.typography.bodySmall,
        )
        @Composable
        fun choice(label: String, selected: Int?, onPick: (Int) -> Unit) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                header.forEachIndexed { i, name ->
                    FilterChip(
                        selected = selected == i,
                        onClick = { onPick(i) },
                        label = { Text(name.ifBlank { "Column ${i + 1}" }, maxLines = 1) },
                    )
                }
            }
        }
        choice("Date", date) { date = it }
        choice("Amount (minus = money out)", amount) { amount = it }
        choice("Description / payee (optional)", party) { party = it }
        Button(
            onClick = { onContinue(BankCsvParser.Columns(date = date!!, amount = amount, party = party)) },
            enabled = date != null && amount != null,
        ) { Text("Continue") }
    }
}
