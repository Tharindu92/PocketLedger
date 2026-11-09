package com.billbook.app.ui.edit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billbook.app.BillBookApp
import com.billbook.app.data.Categories
import com.billbook.app.data.TxType
import com.billbook.app.ui.components.DateField
import com.billbook.app.ui.components.DisplayDate
import com.billbook.app.ui.components.SuggestField
import com.billbook.app.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(id: Long, type: TxType, imagePath: String?, detectedId: Long = 0, onDone: () -> Unit) {
    val vm: EditViewModel = viewModel(factory = EditViewModel.factory(id, type, imagePath, detectedId))
    val form = vm.form
    val used by vm.usedCategories.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var showImage by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }

    val income = form.type == TxType.INCOME
    val categoryOptions = (Categories.defaultsFor(form.type) +
        used.filter { it.type == form.type }.map { it.category }).distinct()
    val title = when {
        vm.isExisting -> "Edit entry"
        income -> "New income"
        else -> "New expense"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (vm.isExisting) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                    IconButton(onClick = { vm.save(onDone) }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            form.imagePath?.let { path ->
                BillImageCard(
                    path = path,
                    scanning = vm.scanning,
                    message = vm.scanMessage,
                    onRescan = vm::rescan,
                    onOpen = { showImage = true },
                )
            }

            if (profiles.size > 1) {
                Text("Profile", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    profiles.forEach { p ->
                        FilterChip(
                            selected = form.profileId == p.id,
                            onClick = { vm.update { it.copy(profileId = p.id) } },
                            label = { Text(p.name) },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !income,
                    onClick = { vm.update { it.copy(type = TxType.EXPENSE) } },
                    label = { Text("Expense") },
                )
                FilterChip(
                    selected = income,
                    onClick = { vm.update { it.copy(type = TxType.INCOME) } },
                    label = { Text("Income") },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.amount,
                    onValueChange = { v -> vm.update { it.copy(amount = v) } },
                    label = { Text("Amount (total)") },
                    isError = vm.amountError != null,
                    supportingText = vm.amountError?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                SuggestField(
                    label = "Currency",
                    value = form.currency,
                    onValueChange = vm::setCurrency,
                    options = (listOf(form.currency).filter { it.length == 3 } + Categories.currencies).distinct(),
                    modifier = Modifier.width(132.dp),
                )
            }
            val hints = vm.suggestions
            OtherMatches(hints.amounts, form.amount, { it }) { v -> vm.update { it.copy(amount = v) } }

            DateField(
                label = if (income) "Date received" else "Bill date",
                date = form.date,
                onDateChange = vm::setDate,
                modifier = Modifier.fillMaxWidth(),
            )
            OtherMatches(hints.dates, form.date, { it.format(DisplayDate) }, onPick = vm::setDate)

            OutlinedTextField(
                value = form.party,
                onValueChange = { v -> vm.update { it.copy(party = v) } },
                label = { Text(if (income) "Received from" else "Business / shop") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OtherMatches(
                hints.parties, form.party, { it },
                // "+" adds a piece to what is already there, for names the scan returned in parts.
                onAppend = if (form.party.isNotBlank()) vm::appendParty else null,
            ) { v -> vm.update { it.copy(party = v) } }

            OutlinedTextField(
                value = form.billNumber,
                onValueChange = { v -> vm.update { it.copy(billNumber = v) } },
                label = { Text(if (income) "Invoice / reference no." else "Bill / invoice no.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OtherMatches(hints.billNumbers, form.billNumber, { it }) { v -> vm.update { it.copy(billNumber = v) } }

            SuggestField(
                label = "Category",
                value = form.category,
                onValueChange = { v -> vm.update { it.copy(category = v) } },
                options = categoryOptions,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.tax,
                onValueChange = { v -> vm.update { it.copy(tax = v) } },
                label = { Text("Tax included (VAT / GST)") },
                prefix = { Text("${form.currency} ") },
                isError = vm.taxError != null,
                supportingText = vm.taxError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            SuggestField(
                label = "Payment method",
                value = form.paymentMethod,
                onValueChange = { v -> vm.update { it.copy(paymentMethod = v) } },
                options = Categories.paymentMethods,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> vm.update { it.copy(notes = v) } },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            form.ocrText?.takeIf { it.isNotBlank() }?.let { text ->
                TextButton(onClick = { showText = !showText }) {
                    Text(if (showText) "Hide scanned text" else "Show scanned text")
                }
                if (showText) {
                    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }

            Button(onClick = { vm.save(onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("The entry and its bill photo will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (showImage) {
        form.imagePath?.let { FullScreenImage(it) { showImage = false } }
    }
}

/**
 * Other values the scan found for a field, one tap to use instead. Hidden when there is nothing
 * besides the value already in the field. With [onAppend], each chip also gets a "+" that adds
 * its text to the field instead of replacing it.
 */
@Composable
private fun <T> OtherMatches(
    options: List<T>,
    current: T,
    label: (T) -> String,
    onAppend: ((T) -> Unit)? = null,
    onPick: (T) -> Unit,
) {
    val others = options.filter { it != current }
    if (others.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = (-8).dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Also found:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        others.forEach { option ->
            SuggestionChip(
                onClick = { onPick(option) },
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.widthIn(max = 260.dp),
            )
            if (onAppend != null) {
                IconButton(onClick = { onAppend(option) }, modifier = Modifier.size(32.dp).offset(x = (-4).dp)) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = "Add \"${label(option)}\" to the name")
                }
            }
        }
    }
}

@Composable
private fun rememberBitmap(path: String, maxDim: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, maxDim) {
        value = withContext(Dispatchers.IO) { ImageFiles.loadBitmap(path, maxDim)?.asImageBitmap() }
    }
    return bitmap
}

@Composable
private fun BillImageCard(
    path: String,
    scanning: Boolean,
    message: String?,
    onRescan: () -> Unit,
    onOpen: () -> Unit,
) {
    val bitmap = rememberBitmap(path, 1200)
    Card(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Bill photo (tap to enlarge)",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                message ?: "Bill photo attached",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRescan, enabled = !scanning) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Re-scan")
            }
        }
    }
}

@Composable
private fun FullScreenImage(path: String, onDismiss: () -> Unit) {
    val bitmap = rememberBitmap(path, 2400)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Bill photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}
