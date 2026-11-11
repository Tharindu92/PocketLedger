package com.billbook.app.ui.bank

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import com.billbook.app.bank.DetectionNotifier
import com.billbook.app.data.Profile
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.billbook.app.bank.InstalledApps
import com.billbook.app.data.DetectedTransaction
import com.billbook.app.data.TxType
import com.billbook.app.ui.components.NotificationDisclosureDialog
import com.billbook.app.ui.theme.MoneyColors
import com.billbook.app.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TimeFormat = DateTimeFormatter.ofPattern("dd MMM, HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankScreen(
    onBack: () -> Unit,
    onChooseApps: () -> Unit,
    onEditDetected: (Long) -> Unit,
    vm: BankViewModel = viewModel(factory = BankViewModel.Factory),
) {
    val context = LocalContext.current
    val installed by vm.installedApps.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfileId by vm.activeProfileId.collectAsStateWithLifecycle()
    var accessGranted by remember { mutableStateOf(InstalledApps.isNotificationAccessGranted(context)) }
    var canNotify by remember { mutableStateOf(DetectionNotifier.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessGranted = InstalledApps.isNotificationAccessGranted(context)
        canNotify = DetectionNotifier.canPost(context)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = DetectionNotifier.canPost(context)
    }
    val settings = (context.applicationContext as com.billbook.app.BillBookApp).settings
    var disclosing by remember { mutableStateOf(false) }
    val watched = vm.watchedApps
    val byPackage = installed.orEmpty().associateBy { it.packageName }

    fun open(intent: android.content.Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Couldn't open that settings page", Toast.LENGTH_SHORT).show()
        }
    }

    if (disclosing) {
        NotificationDisclosureDialog(
            onAgree = {
                disclosing = false
                settings.updateNotificationConsent(true)
                open(InstalledApps.notificationAccessSettings())
            },
            onDismiss = { disclosing = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bank notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "When a banking app you choose shows a notification that you received money, " +
                        "Pocket Ledger reads the amount, currency and sender and lists it here to add as income. " +
                        "Everything stays on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // 1. Notification access
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (accessGranted) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (accessGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (accessGranted) "Notification access is on" else "Notification access is off",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        if (!accessGranted) {
                            Text(
                                "Turn on Pocket Ledger in the list that opens. Android will warn that the app can read " +
                                    "notifications; Pocket Ledger only looks at the apps you choose below.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = {
                                // Google Play: explain and get agreement before Android's access page.
                                if (settings.notificationConsent) open(InstalledApps.notificationAccessSettings())
                                else disclosing = true
                            }) {
                                Text("Turn on notification access")
                            }
                            if (Build.VERSION.SDK_INT >= 33) {
                                Text(
                                    "Switch greyed out? Because the app was installed from an APK, Android blocks it at " +
                                        "first. Open App info → ⋮ (top right) → \"Allow restricted settings\", then try again.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { open(InstalledApps.appInfoSettings(context)) }) {
                                    Text("Open App info")
                                }
                            }
                        } else {
                            TextButton(onClick = { open(InstalledApps.notificationAccessSettings()) }) {
                                Text("Manage")
                            }
                        }
                    }
                }
            }

            // 1b. Pocket Ledger's own notifications (the "which profile?" buttons)
            if (accessGranted && !canNotify) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Allow Pocket Ledger notifications", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Then, when money arrives, Pocket Ledger notifies you with a button for each profile " +
                                    "(e.g. Personal / Club) so you can file it in one tap.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    open(
                                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    )
                                }
                            }) { Text("Allow notifications") }
                        }
                    }
                }
            }

            // 2. Apps to watch
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Apps to watch", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onChooseApps) { Text(if (watched.isEmpty()) "Choose apps" else "Change") }
                }
            }
            if (watched.isEmpty()) {
                item {
                    Text(
                        "No apps chosen yet. Pick your banking apps (for example N26) from the apps installed on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(watched.sortedBy { byPackage[it]?.label ?: it }, key = { "w_$it" }) { pkg ->
                val app = byPackage[pkg]
                ListItem(
                    leadingContent = { AppIcon(app) },
                    headlineContent = { Text(app?.label ?: pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (installed != null && app == null) {
                        { Text("Not installed any more") }
                    } else {
                        null
                    },
                    trailingContent = {
                        IconButton(onClick = { vm.removeWatchedApp(pkg) }) {
                            Icon(Icons.Default.Close, contentDescription = "Stop watching")
                        }
                    },
                )
            }

            // 3. Options
            item {
                Column {
                    SwitchRow(
                        title = "Also detect payments as expenses",
                        subtitle = "e.g. \"You paid €12.40 at REWE\"",
                        checked = vm.detectExpenses,
                        onChange = vm::setDetectExpenses,
                    )
                    SwitchRow(
                        title = "Add automatically",
                        subtitle = if (profiles.size > 1) {
                            "Not used while you have several profiles — you choose the profile for each payment."
                        } else {
                            "Skip the review step below. You can still edit or delete entries later."
                        },
                        checked = vm.autoAdd,
                        onChange = vm::setAutoAdd,
                    )
                }
            }

            // 4. Review list
            item {
                Text(
                    if (pending.isEmpty()) "Nothing to review" else "To review (${pending.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(pending, key = { "p_${it.id}" }) { d ->
                DetectionCard(
                    detection = d,
                    profiles = profiles,
                    defaultProfileId = activeProfileId,
                    onAdd = { profileId -> vm.add(d, profileId) },
                    onEdit = { onEditDetected(d.id) },
                    onDismiss = { vm.dismiss(d) },
                )
            }

            // 5. Tester
            item { TesterCard(vm) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun DetectionCard(
    detection: DetectedTransaction,
    profiles: List<Profile>,
    defaultProfileId: Long,
    onAdd: (profileId: Long) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var profileId by remember(detection.id) { mutableStateOf(defaultProfileId) }
    val income = detection.type == TxType.INCOME
    val time = Instant.ofEpochMilli(detection.postedAt).atZone(ZoneId.systemDefault()).format(TimeFormat)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (income) "+ " else "− ") + Money.format(detection.amountCents, detection.currency),
                    color = if (income) MoneyColors.income else MoneyColors.expense,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(if (income) "Income" else "Expense", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                listOf(detection.party.ifBlank { null }, detection.appLabel, time).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOf(detection.title, detection.text).filter { it.isNotBlank() }.joinToString(" – "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (profiles.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    profiles.forEach { p ->
                        FilterChip(selected = profileId == p.id, onClick = { profileId = p.id }, label = { Text(p.name) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val target = profiles.find { it.id == profileId }?.name
                Button(onClick = { onAdd(profileId) }) {
                    Text(if (profiles.size > 1 && target != null) "Add to $target" else if (income) "Add income" else "Add expense")
                }
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDismiss) { Text("Ignore") }
            }
        }
    }
}

@Composable
private fun TesterCard(vm: BankViewModel) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Try a notification", style = MaterialTheme.typography.titleSmall)
            Text(
                "Type or paste the text of a bank notification to see what Pocket Ledger would detect.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Text, e.g. You received €50.00 from Max") }, minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                val alert = vm.test(title, text)
                result = if (alert == null) {
                    "Not recognised as money received or paid."
                } else {
                    val kind = if (alert.type == TxType.INCOME) "Income" else "Expense"
                    "$kind: ${Money.format(alert.amountCents, alert.currency ?: "?")}" +
                        (alert.party?.let { " · ${if (alert.type == TxType.INCOME) "from" else "to"} $it" } ?: "")
                }
            }) { Text("Test") }
            result?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
