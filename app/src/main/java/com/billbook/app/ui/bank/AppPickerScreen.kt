package com.billbook.app.ui.bank

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billbook.app.bank.InstalledApp

/** Lists the apps installed on this phone so the user can tick their banking / payment apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(onDone: () -> Unit, vm: BankViewModel = viewModel(factory = BankViewModel.Factory)) {
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(vm.watchedApps) }
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose banking apps") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.saveWatchedApps(selected); onDone() }) {
                        Text("Save (${selected.size})")
                    }
                },
            )
        },
    ) { padding ->
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val filtered = list.filter {
            query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        val suggested = filtered.filter { it.looksFinancial }
        val others = filtered.filterNot { it.looksFinancial }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search installed apps") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (suggested.isNotEmpty()) {
                item { SectionHeader("Looks like banking / payment apps") }
                items(suggested, key = { "s_" + it.packageName }) { app ->
                    AppRow(app, app.packageName in selected) { checked ->
                        selected = if (checked) selected + app.packageName else selected - app.packageName
                    }
                }
            }
            item { SectionHeader(if (suggested.isEmpty()) "Installed apps" else "All other apps") }
            items(others, key = { "o_" + it.packageName }) { app ->
                AppRow(app, app.packageName in selected) { checked ->
                    selected = if (checked) selected + app.packageName else selected - app.packageName
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun AppIcon(app: InstalledApp?, modifier: Modifier = Modifier.size(36.dp)) {
    val icon = app?.icon
    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = modifier)
    else Icon(Icons.Default.Android, contentDescription = null, modifier = modifier)
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        leadingContent = { AppIcon(app) },
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = { Checkbox(checked = checked, onCheckedChange = onCheckedChange) },
    )
}
