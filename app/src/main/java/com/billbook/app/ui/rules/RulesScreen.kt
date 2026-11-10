package com.billbook.app.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billbook.app.BillBookApp
import com.billbook.app.data.TxType
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** Keyword → category rules the user asked to remember; each can be deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as BillBookApp
    val dao = remember { app.database.categoryRules() }
    val rules by remember { dao.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Category rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "When you pick a category yourself, Pocket Ledger asks whether to remember it. " +
                            "Entries whose payee or text contains the keyword then get that category, " +
                            "for imports, scans and bank notifications. Rules beat every other guess.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (rules.isEmpty()) {
                        Text("No rules yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
                    }
                }
                HorizontalDivider()
            }
            items(rules, key = { "${it.keyword}|${it.type}" }) { rule ->
                ListItem(
                    headlineContent = { Text("“${rule.keyword}” → ${rule.category}") },
                    supportingContent = { Text(if (rule.type == TxType.INCOME) "Income" else "Expenses") },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { dao.delete(rule.keyword, rule.type) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete rule")
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
