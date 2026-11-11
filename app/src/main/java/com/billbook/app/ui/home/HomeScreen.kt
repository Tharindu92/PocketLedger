package com.billbook.app.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.billbook.app.security.AppLock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billbook.app.BillBookApp
import com.billbook.app.data.AppSettings
import com.billbook.app.data.Categories
import com.billbook.app.ui.components.SuggestField
import com.billbook.app.ui.components.NewCategoryChip
import com.billbook.app.ui.components.OneTimeTip
import com.billbook.app.ui.components.Tips
import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import com.billbook.app.data.date
import com.billbook.app.ui.components.DisplayDate
import com.billbook.app.ui.theme.MoneyColors
import com.billbook.app.util.ImageFiles
import com.billbook.app.util.Money
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpen: (Long) -> Unit,
    onAdd: (TxType) -> Unit,
    onBillImage: (String) -> Unit,
    onStatement: () -> Unit,
    onInsights: () -> Unit,
    onBank: () -> Unit,
    onProfiles: () -> Unit,
    onRules: () -> Unit = {},
    onCategories: () -> Unit = {},
    onHelp: () -> Unit = {},
    onBackup: () -> Unit = {},
    onImportStatement: (Uri) -> Unit = {},
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    var profileMenu by remember { mutableStateOf(false) }
    val pendingDetections by vm.pendingDetections.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = (context.applicationContext as BillBookApp).settings
    var showSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val pickStatement = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportStatement(uri)
    }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingPhoto
        pendingPhoto = null
        if (path != null) {
            if (success && File(path).length() > 0) onBillImage(path) else File(path).delete()
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { ImageFiles.importFromUri(context, uri) }
            if (path != null) onBillImage(path)
            else Toast.makeText(context, "Could not open that image", Toast.LENGTH_SHORT).show()
        }
    }

    fun scanWithCamera() {
        val file = ImageFiles.newImageFile(context)
        pendingPhoto = file.absolutePath
        try {
            takePicture.launch(ImageFiles.uriFor(context, file))
        } catch (e: ActivityNotFoundException) {
            pendingPhoto = null
            Toast.makeText(context, "No camera app found", Toast.LENGTH_SHORT).show()
        }
    }

    fun pickFromGallery() =
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    // Google's document scanner crops the bill out of its background (table, newspaper…) and
    // straightens it, which makes the text far easier to read than a plain photo.
    val documentScanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build()
        )
    }
    val scanDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()?.imageUri
            ?: return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { ImageFiles.importFromUri(context, uri) }
            if (path != null) onBillImage(path)
            else Toast.makeText(context, "Could not open the scan", Toast.LENGTH_SHORT).show()
        }
    }

    /** Document scanner when available (needs Google Play services), plain camera photo otherwise. */
    fun scanBill() {
        val activity = context.findActivity()
        if (activity == null) {
            scanWithCamera()
            return
        }
        documentScanner.getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanDocument.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { scanWithCamera() }
    }

    val selected by vm.selected.collectAsStateWithLifecycle()
    val selecting = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    var pickProfile by remember { mutableStateOf(false) }
    val categories by vm.categories.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    fun announce(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                val allShown = state.items.isNotEmpty() && state.items.all { it.id in selected }
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = { Text("${selected.size} selected") },
                    actions = {
                        IconButton(onClick = vm::toggleSelectAll) {
                            Icon(
                                if (allShown) Icons.Default.Deselect else Icons.Default.SelectAll,
                                contentDescription = if (allShown) "Select none" else "Select all",
                            )
                        }
                        IconButton(onClick = { pickCategory = true }) {
                            Icon(Icons.Default.Category, contentDescription = "Change category")
                        }
                        if (profiles.size > 1) {
                            IconButton(onClick = { pickProfile = true }) {
                                Icon(Icons.Default.DriveFileMove, contentDescription = "Move to profile")
                            }
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            } else TopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier.clickable { profileMenu = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Pocket Ledger", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    activeProfile?.name ?: "Personal",
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch profile")
                        }
                        DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                            profiles.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    leadingIcon = if (p.id == activeProfile?.id) {
                                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        vm.selectProfile(p.id)
                                        profileMenu = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Add / manage profiles") },
                                leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) },
                                onClick = {
                                    profileMenu = false
                                    onProfiles()
                                },
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onBank) {
                        Icon(Icons.Default.AccountBalance, contentDescription = "Bank notifications")
                    }
                    IconButton(onClick = onInsights) {
                        Icon(Icons.Default.Insights, contentDescription = "Insights")
                    }
                    IconButton(onClick = onStatement) {
                        Icon(Icons.Default.Description, contentDescription = "Statement")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!selecting) ExtendedFloatingActionButton(
                onClick = { showSheet = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (pendingDetections > 0) {
                item {
                    Card(
                        onClick = onBank,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountBalance, contentDescription = null)
                            Spacer(Modifier.padding(6.dp))
                            Text(
                                if (pendingDetections == 1) "1 bank payment detected — tap to review"
                                else "$pendingDetections bank payments detected — tap to review",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item { MonthSelector(state.month, vm::previousMonth, vm::nextMonth) }
            item { SummaryCard(state, settings.currency) }
            item { FilterRow(state.filter, vm::setFilter) }
            if (state.items.isEmpty()) {
                item { EmptyState(onScan = ::scanBill, onManual = { onAdd(TxType.EXPENSE) }) }
            }
            if (state.items.isNotEmpty() && !selecting) {
                item {
                    OneTimeTip(
                        Tips.HOME_SELECT,
                        "Tap an entry to edit it. Long-press to select several and change their category, " +
                            "move them to another profile or delete them.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(state.items, key = { it.id }) { t ->
                TransactionRow(
                    t,
                    t.currency.ifBlank { settings.currency },
                    selected = if (selecting) t.id in selected else null,
                    onClick = { if (selecting) vm.toggleSelection(t.id) else onOpen(t.id) },
                    onLongClick = { vm.toggleSelection(t.id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete) {
        val count = state.items.count { it.id in selected }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (count == 1) "Delete 1 entry?" else "Delete $count entries?") },
            text = { Text("Their bill photos are deleted too. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected { n -> announce("${entries(n)} deleted") }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (pickCategory) {
        val chosen = state.items.filter { it.id in selected }
        // Expense and income categories differ, so offer each list only for the types that are ticked.
        val types = TxType.entries.filter { type -> chosen.any { it.type == type } }
        AlertDialog(
            onDismissRequest = { pickCategory = false },
            title = { Text("Category for ${entries(chosen.size)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    types.forEach { type ->
                        val count = chosen.count { it.type == type }
                        val current = chosen.filter { it.type == type }.map { it.category }.distinct().singleOrNull()
                        Text(
                            (if (type == TxType.INCOME) "Income" else "Expenses") +
                                if (types.size > 1) " (${entries(count)})" else "",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            categories[type].orEmpty().forEach { name ->
                                FilterChip(
                                    selected = name == current,
                                    onClick = {
                                        if (types.size == 1) pickCategory = false
                                        vm.setCategory(type, name) { n -> announce("${entries(n)} set to $name") }
                                    },
                                    label = { Text(name) },
                                )
                            }
                            NewCategoryChip(type, categories[type].orEmpty()) { name ->
                                if (types.size == 1) pickCategory = false
                                vm.setCategory(type, name) { n -> announce("${entries(n)} set to $name") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickCategory = false }) { Text("Done") } },
        )
    }

    if (pickProfile) {
        AlertDialog(
            onDismissRequest = { pickProfile = false },
            title = { Text("Move ${entries(state.items.count { it.id in selected })} to") },
            text = {
                Column {
                    profiles.filter { it.id != activeProfile?.id }.forEach { p ->
                        ListItem(
                            modifier = Modifier.clickable {
                                pickProfile = false
                                vm.moveToProfile(p.id) { n -> announce("${entries(n)} moved to ${p.name}") }
                            },
                            leadingContent = { Icon(Icons.Default.Group, contentDescription = null) },
                            headlineContent = { Text(p.name) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickProfile = false }) { Text("Cancel") } },
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            SheetOption(Icons.Default.CameraAlt, "Scan a bill", "Crops the bill out of the background — amount, date and bill no. are read automatically") {
                showSheet = false; scanBill()
            }
            SheetOption(Icons.Default.PhotoLibrary, "Upload a bill photo", "Pick an existing photo from the gallery") {
                showSheet = false; pickFromGallery()
            }
            SheetOption(
                Icons.Default.AccountBalance,
                "Import bank statement",
                "CSV or PDF from your bank — choose which bookings go to which profile",
            ) {
                showSheet = false
                try {
                    pickStatement.launch(STATEMENT_TYPES)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "No file manager available", Toast.LENGTH_SHORT).show()
                }
            }
            SheetOption(Icons.Default.Edit, "Add expense manually", null) {
                showSheet = false; onAdd(TxType.EXPENSE)
            }
            SheetOption(Icons.Default.AttachMoney, "Add income", null) {
                showSheet = false; onAdd(TxType.INCOME)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings,
            onRules = { showSettings = false; onRules() },
            onCategories = { showSettings = false; onCategories() },
            onHelp = { showSettings = false; onHelp() },
            onBackup = { showSettings = false; onBackup() },
        ) { showSettings = false }
    }
}

private val MonthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
private fun MonthSelector(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            month.format(MonthFormat),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun SummaryCard(state: HomeState, defaultCurrency: String) {
    // Default currency first (shown even when empty), then any other currencies used this month.
    val totals = listOf(state.totals.find { it.currency == defaultCurrency } ?: CurrencyTotals(defaultCurrency)) +
        state.totals.filter { it.currency != defaultCurrency }.sortedBy { it.currency }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        totals.forEachIndexed { index, t ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                SummaryValue("Income", t.income, t.currency, MoneyColors.income, Modifier.weight(1f))
                SummaryValue("Expenses", t.expense, t.currency, MoneyColors.expense, Modifier.weight(1f))
                SummaryValue(
                    "Net", t.net, t.currency,
                    if (t.net >= 0) MoneyColors.income else MoneyColors.expense,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, cents: Long, currency: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            Money.format(cents),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(currency, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FilterRow(filter: TxType?, onFilter: (TxType?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("All") })
        FilterChip(selected = filter == TxType.EXPENSE, onClick = { onFilter(TxType.EXPENSE) }, label = { Text("Expenses") })
        FilterChip(selected = filter == TxType.INCOME, onClick = { onFilter(TxType.INCOME) }, label = { Text("Income") })
    }
}

/** [selected] is null outside selection mode; otherwise a tick box replaces the icon. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(
    t: Transaction,
    currency: String,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val income = t.type == TxType.INCOME
    val title = t.party.ifBlank { t.category.ifBlank { if (income) "Income" else "Expense" } }
    val details = listOfNotNull(
        t.date.format(DisplayDate),
        t.category.takeIf { it.isNotBlank() && t.party.isNotBlank() },
        t.billNumber.takeIf { it.isNotBlank() }?.let { "#$it" },
    ).joinToString(" · ")

    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected == true) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                Icon(
                    if (t.imagePath != null) Icons.Default.Receipt else Icons.Outlined.Payments,
                    contentDescription = if (t.imagePath != null) "Has bill photo" else null,
                )
            }
        },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Text(
                (if (income) "+ " else "− ") + Money.format(t.amountCents, currency),
                color = if (income) MoneyColors.income else MoneyColors.expense,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

@Composable
private fun EmptyState(onScan: () -> Unit, onManual: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("No entries this month", style = MaterialTheme.typography.titleMedium)
        Text(
            "Scan a bill to capture it automatically, or add an entry by hand.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onScan) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text("Scan a bill")
        }
        TextButton(onClick = onManual) { Text("Add manually") }
    }
}

@Composable
private fun SheetOption(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
    )
}

@Composable
private fun SettingsDialog(settings: AppSettings, onRules: () -> Unit, onCategories: () -> Unit, onHelp: () -> Unit, onBackup: () -> Unit, onDismiss: () -> Unit) {
    var currency by remember { mutableStateOf(settings.currency) }
    var business by remember { mutableStateOf(settings.businessName) }
    var onlineRates by remember { mutableStateOf(settings.onlineRates == true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = business,
                    onValueChange = { business = it },
                    label = { Text("Business / owner name") },
                    supportingText = { Text("Shown at the top of statements") },
                    singleLine = true,
                )
                SuggestField(
                    label = "Default currency",
                    value = currency,
                    onValueChange = { v -> currency = v.filter { it.isLetter() }.uppercase().take(3) },
                    options = Categories.currencies,
                )
                Text(
                    "Used for new entries. Scanned bills use the currency printed on the bill; " +
                        "you can change it on any entry.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Download exchange rates", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "For statements that combine currencies. Only currency codes and dates are sent.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = onlineRates, onCheckedChange = { onlineRates = it })
                }
                AppLockRow(settings)
                TextButton(onClick = onHelp) { Text("How Pocket Ledger works") }
                TextButton(onClick = onCategories) { Text("Categories: add, rename, hide") }
                TextButton(onClick = onRules) { Text("Category rules you saved") }
                TextButton(onClick = onBackup) { Text("Backup and restore") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                settings.update(currency, business)
                // Leave "not asked yet" alone unless the user actually flipped the switch.
                if (onlineRates != (settings.onlineRates == true)) settings.updateOnlineRates(onlineRates)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Turns the app lock on (after checking the fingerprint / screen lock works) or off, straight away. */
@Composable
private fun AppLockRow(settings: AppSettings) {
    val context = LocalContext.current
    val available = remember { AppLock.isAvailable(context) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Lock the app", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (available || settings.appLock) {
                    "Ask for fingerprint, face or screen lock when opening Pocket Ledger, and hide it in recent apps."
                } else {
                    "Set up a screen lock on this phone first."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = settings.appLock,
            enabled = available || settings.appLock,
            onCheckedChange = { on ->
                if (!on) {
                    settings.updateAppLock(false)
                } else {
                    (context.findActivity() as? FragmentActivity)?.let { activity ->
                        AppLock.authenticate(activity, "Turn on app lock", onSuccess = {
                            settings.updateAppLock(true)
                            Toast.makeText(context, "App lock is on", Toast.LENGTH_SHORT).show()
                        })
                    }
                }
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Bank statement files: CSV in its various MIME types, and PDF. */
private val STATEMENT_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "text/plain", "application/csv",
    "application/vnd.ms-excel", "application/pdf", "application/octet-stream",
)
