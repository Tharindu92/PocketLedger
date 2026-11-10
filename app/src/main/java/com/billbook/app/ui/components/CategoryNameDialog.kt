package com.billbook.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * Asks for a category name. With [mergeHint], typing a name in [existing] turns the button into
 * "Merge into …"; otherwise an existing name just selects that category.
 */
@Composable
fun CategoryNameDialog(
    title: String,
    initial: String,
    existing: List<String>,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    mergeHint: Boolean = false,
) {
    var name by remember { mutableStateOf(initial) }
    val match = existing.find { it.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                if (match != null) {
                    Text(
                        if (mergeHint) "“$match” already exists: its entries and these will be one category."
                        else "“$match” already exists and will be used.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(match ?: name) }, enabled = name.isNotBlank() && name.trim() != initial) {
                Text(if (match != null && mergeHint) "Merge into $match" else confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
