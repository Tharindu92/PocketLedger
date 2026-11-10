package com.billbook.app.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billbook.app.BillBookApp
import com.billbook.app.data.CategoryCatalog
import com.billbook.app.data.CategoryManager
import com.billbook.app.data.TxType
import com.billbook.app.ui.components.CategoryNameDialog
import kotlinx.coroutines.launch

/** Add, rename / merge, hide and delete categories, separately for expenses and income. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BillBookApp
    val manager = remember { CategoryManager(app.database) }
    val settings by remember { app.database.categorySettings().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val counts by remember { app.database.transactions().observeCategoryCounts() }.collectAsStateWithLifecycle(emptyList())
    val lists by remember { CategoryCatalog.observe(app.database) }.collectAsStateWithLifecycle(emptyMap())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var type by rememberSaveable { mutableStateOf(TxType.EXPENSE) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }

    val shown = lists[type].orEmpty()
    val hidden = settings.filter { it.type == type && it.hidden }.map { it.name }.sorted()
    fun countOf(name: String) = counts.find { it.type == type && it.category == name }?.count ?: 0
    fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add category") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == TxType.EXPENSE, onClick = { type = TxType.EXPENSE }, label = { Text("Expenses") })
                        FilterChip(selected = type == TxType.INCOME, onClick = { type = TxType.INCOME }, label = { Text("Income") })
                    }
                    Text(
                        "Rename to an existing name to merge two categories. Hiding only removes a category " +
                            "from the pick lists; entries keep it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
            items(shown, key = { "shown|$it" }) { name ->
                val locked = CategoryCatalog.isLocked(name)
                val builtIn = CategoryCatalog.isBuiltIn(name, type)
                val n = countOf(name)
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                if (builtIn) "Built-in" else "Added by you",
                                entries(n).takeIf { n > 0 },
                                "counts as saved in Insights".takeIf { locked },
                            ).joinToString(" · ")
                        )
                    },
                    trailingContent = {
                        if (locked) {
                            Icon(Icons.Default.Lock, contentDescription = "Can't be changed")
                        } else {
                            Row {
                                IconButton(onClick = { renaming = name }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename or merge")
                                }
                                if (builtIn || n > 0) {
                                    IconButton(onClick = { scope.launch { manager.setHidden(name, type, true) } }) {
                                        Icon(Icons.Default.VisibilityOff, contentDescription = "Hide")
                                    }
                                } else {
                                    IconButton(onClick = { scope.launch { manager.delete(name, type) } }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
            if (hidden.isNotEmpty()) {
                item {
                    Text(
                        "Hidden",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(hidden, key = { "hidden|$it" }) { name ->
                    val n = countOf(name)
                    ListItem(
                        headlineContent = { Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        supportingContent = { if (n > 0) Text("${entries(n)} still use it") },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { manager.setHidden(name, type, false) } }) {
                                Icon(Icons.Default.Visibility, contentDescription = "Show again")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) {
        CategoryNameDialog(
            title = "New ${if (type == TxType.INCOME) "income" else "expense"} category",
            initial = "",
            existing = shown + hidden,
            confirm = "Add",
            onConfirm = { name ->
                adding = false
                scope.launch {
                    val added = manager.add(name, type)
                    snackbar.showSnackbar("Added $added")
                }
            },
            onDismiss = { adding = false },
        )
    }
    renaming?.let { old ->
        CategoryNameDialog(
            title = "Rename “$old”",
            initial = old,
            existing = (shown + hidden).filter { it != old },
            confirm = "Rename",
            mergeHint = true,
            onConfirm = { name ->
                renaming = null
                scope.launch {
                    val n = manager.rename(old, name, type)
                    snackbar.showSnackbar("“$old” is now “${manager.clean(name)}”" + if (n > 0) " on ${entries(n)}" else "")
                }
            },
            onDismiss = { renaming = null },
        )
    }
}
