package com.billbook.app.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.billbook.app.BillBookApp
import com.billbook.app.data.Profile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(private val app: BillBookApp) : ViewModel() {
    private val dao = app.database.profiles()

    val profiles: StateFlow<List<Profile>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeProfileId: StateFlow<Long> = app.settings.activeProfileId

    fun add(name: String) {
        viewModelScope.launch {
            val id = dao.insert(Profile(name = name.trim()))
            app.settings.setActiveProfile(id)
        }
    }

    fun rename(profile: Profile, name: String) {
        viewModelScope.launch { dao.rename(profile.id, name.trim()) }
    }

    fun select(profile: Profile) = app.settings.setActiveProfile(profile.id)

    /** Returns an error message, or null when deleted. Profiles with entries are kept. */
    suspend fun delete(profile: Profile): String? {
        if (profile.id == Profile.DEFAULT_ID) return "\"${profile.name}\" is the main profile and can't be deleted."
        val count = dao.entryCount(profile.id)
        if (count > 0) return "\"${profile.name}\" still has $count entries. Delete or move them first."
        dao.delete(profile.id)
        if (app.settings.activeProfileId.value == profile.id) app.settings.setActiveProfile(Profile.DEFAULT_ID)
        return null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ProfilesViewModel(this[APPLICATION_KEY] as BillBookApp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(onBack: () -> Unit, vm: ProfilesViewModel = viewModel(factory = ProfilesViewModel.Factory)) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeProfileId.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New profile") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Text(
                    "Keep separate accounts, e.g. Personal and your club. Each entry belongs to one profile, " +
                        "and statements are made per profile. Tap a profile to switch to it.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(profiles, key = { it.id }) { p ->
                ListItem(
                    modifier = Modifier.clickable { vm.select(p) },
                    leadingContent = {
                        Icon(if (p.id == activeId) Icons.Default.Check else Icons.Default.Person, contentDescription = null)
                    },
                    headlineContent = { Text(p.name) },
                    supportingContent = if (p.id == activeId) {
                        { Text("Current profile") }
                    } else {
                        null
                    },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            IconButton(onClick = { editing = p }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename")
                            }
                            if (p.id != Profile.DEFAULT_ID) {
                                IconButton(onClick = { deleting = p }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    if (adding) {
        NameDialog(title = "New profile", initial = "", onDismiss = { adding = false }) { name ->
            vm.add(name)
            adding = false
        }
    }
    editing?.let { p ->
        NameDialog(title = "Rename profile", initial = p.name, onDismiss = { editing = null }) { name ->
            vm.rename(p, name)
            editing = null
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("Only empty profiles can be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { vm.delete(p)?.let { snackbar.showSnackbar(it) } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name, e.g. Cricket Club") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
