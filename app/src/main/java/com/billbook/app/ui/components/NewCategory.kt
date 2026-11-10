package com.billbook.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.billbook.app.BillBookApp
import com.billbook.app.data.CategoryManager
import com.billbook.app.data.TxType
import kotlinx.coroutines.launch

/** Saves a new category for [type] and hands back its cleaned-up name (an existing one is reused). */
@Composable
fun NewCategoryDialog(type: TxType, existing: List<String>, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as BillBookApp
    val manager = remember { CategoryManager(app.database) }
    val scope = rememberCoroutineScope()
    CategoryNameDialog(
        title = "New ${if (type == TxType.INCOME) "income" else "expense"} category",
        initial = "",
        existing = existing,
        confirm = "Add",
        onConfirm = { name ->
            scope.launch {
                val clean = manager.add(name, type)
                onDismiss()
                if (clean.isNotEmpty()) onCreated(clean)
            }
        },
        onDismiss = onDismiss,
    )
}

/** "+ New" chip for the end of a category chip list. */
@Composable
fun NewCategoryChip(type: TxType, existing: List<String>, onCreated: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text("New") },
        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
    )
    if (open) NewCategoryDialog(type, existing, onCreated, onDismiss = { open = false })
}
