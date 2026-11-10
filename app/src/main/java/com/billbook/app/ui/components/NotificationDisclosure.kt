package com.billbook.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What notification access is used for, shown before Android's own access page (Google Play requirement). */
private val DISCLOSURE_POINTS = listOf(
    "Pocket Ledger reads notifications only from the banking and payment apps you choose.",
    "From those it takes the amount, currency, payee and time of a payment, to suggest an entry.",
    "Notifications from all other apps are ignored and not stored.",
    "Nothing is sent anywhere: everything stays on this phone.",
    "You can turn it off any time in Android settings or on the Bank notifications screen.",
)

@Composable
fun NotificationDisclosureText() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DISCLOSURE_POINTS.forEach { point ->
            Row {
                Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                Text(point, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Asked before opening notification access settings when the user hasn't agreed yet. */
@Composable
fun NotificationDisclosureDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reading bank notifications") },
        text = { NotificationDisclosureText() },
        confirmButton = { TextButton(onClick = onAgree) { Text("Agree and continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
