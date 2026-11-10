package com.billbook.app.ui.help

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.billbook.app.BillBookApp

private data class Section(val title: String, val points: List<String>)

private val SECTIONS = listOf(
    Section(
        "Adding bills and income",
        listOf(
            "Tap Add → Scan a bill. The scanner crops the bill out of the background; amount, date, business and bill number are read automatically.",
            "If something is read wrong, tap one of the other suggestions under that field. For a business name, ⊕ adds that piece to the name.",
            "You can also upload a photo from the gallery, or add an expense or income by hand.",
            "Paid by card in another currency? Turn on \"Paid in another currency\" and enter what your bank charged.",
        ),
    ),
    Section(
        "Importing a bank statement",
        listOf(
            "Tap Add → Import bank statement and choose the CSV or PDF from your bank. CSV gives the most reliable result.",
            "Every booking is listed: untick the ones you don't want. Bookings that look already recorded are unticked for you.",
            "Choose a profile, income/expense and category per booking, or use the buttons above the list for all ticked bookings at once.",
            "Rows marked \"check\" were partly guessed (direction or date): look at them before importing.",
        ),
    ),
    Section(
        "Categories and remembered choices",
        listOf(
            "Categories are guessed from the payee and description (e.g. REWE → Groceries).",
            "When you pick a category yourself, Pocket Ledger asks whether to remember it. Shorten the keyword (e.g. \"REWE\") so it matches more. Remembered choices win over every guess.",
            "Settings → Categories: add your own, rename, merge two into one, or hide ones you don't use.",
            "Settings → Category rules: see and delete what was remembered.",
        ),
    ),
    Section(
        "Profiles",
        listOf(
            "Tap the profile name at the top of the home screen to switch, e.g. between Personal and a club.",
            "Each entry belongs to one profile; statements and Insights can show one profile or all together.",
        ),
    ),
    Section(
        "Changing several entries at once",
        listOf(
            "On the home list, long-press an entry, then tap others to tick them.",
            "The top bar then lets you select all, change the category, move to another profile or delete.",
        ),
    ),
    Section(
        "Bank notifications",
        listOf(
            "Optional. When a banking app you choose shows a payment, Pocket Ledger suggests it as an entry.",
            "Only the apps you choose are read, and only amount, currency, payee and time are taken. Nothing leaves the phone.",
            "Set it up on the Bank notifications screen (bank icon at the top of the home screen).",
        ),
    ),
    Section(
        "Insights",
        listOf(
            "Chart icon at the top of the home screen. Shows income, spending, savings, and spending by category.",
            "Pick a period with the chips, step with ◀ ▶, or tap Choose… for one month or a range.",
            "Money moved into Investments counts as saved, not spent.",
            "Tap a category to see the biggest payees in it.",
        ),
    ),
    Section(
        "Statements and currencies",
        listOf(
            "Statement icon at the top of the home screen: pick a period and currencies, preview, then share or save as PDF or CSV.",
            "To combine currencies, choose the currency to report in. Entries are converted with the amount your card was charged, or with exchange rates (downloaded only if you allow it, or typed in by you).",
        ),
    ),
    Section(
        "Your data",
        listOf(
            "Everything is stored on this phone only. There is no account and no server.",
            "The only internet use is downloading exchange rates, if you allow it: only currency codes and dates are sent.",
            "If Android backup is turned on for your Google account, your entries can be part of that backup.",
            "Uninstalling the app deletes its data.",
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, onShowWelcome: () -> Unit) {
    val settings = (LocalContext.current.applicationContext as BillBookApp).settings
    var open by rememberSaveable { mutableStateOf(0) }
    var tipsReset by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How Pocket Ledger works") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(SECTIONS) { index, section ->
                val expanded = open == index
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                ) {
                    Column(Modifier.clickable { open = if (expanded) -1 else index }.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(section.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                        }
                        if (expanded) {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                section.points.forEach { point ->
                                    Row {
                                        Text("•", modifier = Modifier.padding(end = 8.dp))
                                        Text(point, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShowWelcome, modifier = Modifier.fillMaxWidth()) { Text("Show the welcome screens again") }
                    OutlinedButton(
                        onClick = { settings.resetTips(); tipsReset = true },
                        enabled = !tipsReset,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (tipsReset) "Tips will show again" else "Show tips again") }
                }
            }
        }
    }
}
