package com.billbook.app.ui.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.billbook.app.BillBookApp
import com.billbook.app.bank.BankAlert
import com.billbook.app.bank.BankNotificationParser
import com.billbook.app.bank.InstalledApp
import com.billbook.app.bank.InstalledApps
import com.billbook.app.data.DetectedTransaction
import com.billbook.app.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BankViewModel(private val app: BillBookApp) : ViewModel() {
    private val settings = app.settings

    private val _installedApps = MutableStateFlow<List<InstalledApp>?>(null)
    /** Null while loading. */
    val installedApps: StateFlow<List<InstalledApp>?> = _installedApps.asStateFlow()

    val pending: StateFlow<List<DetectedTransaction>> = app.database.detected().observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watchedApps: Set<String> get() = settings.watchedApps
    val detectExpenses: Boolean get() = settings.detectExpenses
    val autoAdd: Boolean get() = settings.autoAddDetected

    init {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) { InstalledApps.load(app) }
        }
    }

    fun saveWatchedApps(packages: Set<String>) = settings.updateWatchedApps(packages)
    fun removeWatchedApp(packageName: String) = settings.updateWatchedApps(settings.watchedApps - packageName)
    fun setDetectExpenses(enabled: Boolean) = settings.updateDetectExpenses(enabled)
    fun setAutoAdd(enabled: Boolean) = settings.updateAutoAdd(enabled)

    val profiles: StateFlow<List<Profile>> = app.database.profiles().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeProfileId: StateFlow<Long> = settings.activeProfileId

    fun add(detection: DetectedTransaction, profileId: Long) {
        viewModelScope.launch { app.bankAlerts.add(detection, profileId) }
    }

    fun dismiss(detection: DetectedTransaction) {
        viewModelScope.launch { app.bankAlerts.dismiss(detection.id) }
    }

    /** Lets the user check how a notification text would be read. */
    fun test(title: String, text: String): BankAlert? =
        BankNotificationParser.parse(title, text, settings.currency, detectExpenses = true)

    companion object {
        val Factory = viewModelFactory {
            initializer { BankViewModel(this[APPLICATION_KEY] as BillBookApp) }
        }
    }
}
