package com.billbook.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.billbook.app.BillBookApp

/** Ids of the one-time tips, so they can be listed and reset in one place. */
object Tips {
    const val HOME_SELECT = "home_select"
    const val IMPORT_REMEMBER = "import_remember"
    const val INSIGHTS_PERIOD = "insights_period"
    const val SCAN_SUGGESTIONS = "scan_suggestions"
}

/** A small hint shown until the user closes it with ✕; it then never comes back (unless tips are reset). */
@Composable
fun OneTimeTip(id: String, text: String, modifier: Modifier = Modifier) {
    val settings = (LocalContext.current.applicationContext as BillBookApp).settings
    if (id in settings.seenTips) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = { settings.dismissTip(id) }) {
                Icon(Icons.Default.Close, contentDescription = "Close tip",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}
