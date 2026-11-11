package com.billbook.app.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.billbook.app.BillBookApp
import com.billbook.app.backup.BackupManager
import com.billbook.app.backup.WrongPasswordException
import com.billbook.app.util.Limits
import com.billbook.app.util.TooLargeException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

private const val MIN_PASSWORD = 6

private sealed interface Restore {
    data object NeedsPassword : Restore
    data class Confirm(val summary: BackupManager.Summary) : Restore
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BillBookApp
    val backups = remember { BackupManager(app) }
    val scope = rememberCoroutineScope()

    var protect by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var restore by remember { mutableStateOf<Restore?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { if (busy == null) backups.discard() } }

    fun describe(e: Exception) = when (e) {
        is TooLargeException -> "The file is larger than ${Limits.mb(e.limit)} MB."
        else -> e.message ?: "Unknown error"
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Saving backup…"; error = null; message = null
            try {
                val s = backups.export(uri, if (protect) password.toCharArray() else null)
                message = "Saved: ${s.entries} entries, ${s.profiles} profiles, ${s.photos} bill photos. " +
                    "Keep the file somewhere other than this phone, e.g. Google Drive."
                password = ""; repeat = ""
            } catch (e: Exception) {
                error = "Couldn't save the backup: ${describe(e)}"
            } finally {
                busy = null
            }
        }
    }

    fun unlock(pw: CharArray?) = scope.launch {
        busy = "Checking backup…"; restoreError = null
        try {
            restore = Restore.Confirm(backups.read(pw))
            restorePassword = ""
        } catch (e: WrongPasswordException) {
            restore = Restore.NeedsPassword
            restoreError = "Wrong password."
        } catch (e: Exception) {
            restore = null
            backups.discard()
            error = "Can't restore this file: ${describe(e)}"
        } finally {
            busy = null
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Opening backup…"; error = null; message = null
            try {
                if (backups.open(uri)) {
                    busy = null
                    restore = Restore.NeedsPassword
                } else {
                    unlock(null)
                }
            } catch (e: Exception) {
                error = "Can't restore this file: ${describe(e)}"
                busy = null
            }
        }
    }

    val passwordOk = !protect || (password.length >= MIN_PASSWORD && password == repeat)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup and restore") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = busy == null) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "A backup file holds all your entries, profiles, categories, rules, settings and bill photos. " +
                    "Use it to move to a new phone or to keep a copy you control.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Section("Save a backup") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Protect with a password", style = MaterialTheme.typography.bodyLarge)
                        Text("Recommended. The file is encrypted and can't be read without it.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = protect, onCheckedChange = { protect = it })
                }
                if (protect) {
                    PasswordField("Password", password, { password = it },
                        supporting = "At least $MIN_PASSWORD characters")
                    PasswordField("Repeat password", repeat, { repeat = it },
                        supporting = if (repeat.isNotEmpty() && repeat != password) "Doesn't match" else null,
                        isError = repeat.isNotEmpty() && repeat != password)
                    Text(
                        "If you forget this password, the backup can't be opened. Nobody can recover it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "Without a password, anyone who gets the file can read your entries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { save.launch("PocketLedger-backup-${LocalDate.now()}.plbackup") },
                    enabled = busy == null && passwordOk,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save backup file…") }
            }

            Section("Restore from a backup") {
                Text(
                    "Replaces everything in Pocket Ledger on this phone with the backup. The app restarts afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { pick.launch(arrayOf("*/*")) },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Choose backup file…") }
            }

            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(it)
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    when (val r = restore) {
        Restore.NeedsPassword -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This backup is password-protected.")
                    PasswordField("Password", restorePassword, { restorePassword = it; restoreError = null },
                        supporting = restoreError, isError = restoreError != null)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { unlock(restorePassword.toCharArray()) },
                    enabled = busy == null && restorePassword.isNotEmpty(),
                ) { Text(if (busy != null) "Checking…" else "Open") }
            },
            dismissButton = {
                TextButton(onClick = { restore = null; restorePassword = ""; backups.discard() }, enabled = busy == null) {
                    Text("Cancel")
                }
            },
        )
        is Restore.Confirm -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Replace all data?") },
            text = {
                val s = r.summary
                val made = if (s.created > 0) DateFormat.getDateTimeInstance().format(Date(s.created)) else "unknown date"
                Text(
                    "Backup from $made:\n${s.entries} entries, ${s.profiles} profiles, ${s.photos} bill photos.\n\n" +
                        "Everything currently in Pocket Ledger on this phone will be replaced. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restore = null
                    scope.launch {
                        busy = "Restoring…"
                        try {
                            backups.restoreAndRestart(context)
                        } catch (e: Exception) {
                            busy = null
                            error = "${describe(e)} Close and reopen Pocket Ledger."
                        }
                    }
                }) { Text("Replace and restart", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { restore = null; backups.discard() }) { Text("Cancel") }
            },
        )
        null -> {}
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    supporting: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
