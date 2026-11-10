package com.billbook.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.billbook.app.data.CategoryRule
import com.billbook.app.data.CategoryGuesser
import com.billbook.app.data.TxType

/** Asking whether to remember "[keyword] → [category]" for future entries of [type]. */
data class RuleOffer(val keyword: String, val type: TxType, val category: String) {
    fun toRule(keyword: String) = CategoryRule(CategoryGuesser.normaliseKeyword(keyword), type, category)
}

@Composable
private fun KeywordField(keyword: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = keyword,
        onValueChange = onChange,
        label = { Text("When the payee or text contains") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun question(offer: RuleOffer) =
    "Remember ${offer.category} for future ${if (offer.type == TxType.INCOME) "income" else "expenses"} " +
        "from this payee? You can shorten the keyword so it matches more."

/** Non-blocking card, e.g. above the import button. */
@Composable
fun RememberRuleCard(offer: RuleOffer, onRemember: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var keyword by remember(offer) { mutableStateOf(offer.keyword) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(question(offer), style = MaterialTheme.typography.bodyMedium)
            KeywordField(keyword) { keyword = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = { onRemember(keyword) }, enabled = keyword.isNotBlank()) { Text("Remember") }
            }
        }
    }
}

/** Dialog version, e.g. when saving an entry. */
@Composable
fun RememberRuleDialog(offer: RuleOffer, onRemember: (String) -> Unit, onDismiss: () -> Unit) {
    var keyword by remember(offer) { mutableStateOf(offer.keyword) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remember this category?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(question(offer), style = MaterialTheme.typography.bodyMedium)
                KeywordField(keyword) { keyword = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRemember(keyword) }, enabled = keyword.isNotBlank()) { Text("Remember") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
