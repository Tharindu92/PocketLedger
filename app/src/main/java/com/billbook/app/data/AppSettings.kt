package com.billbook.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small app-wide preferences, observable from Compose. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var currency by mutableStateOf(prefs.getString(KEY_CURRENCY, DEFAULT_CURRENCY) ?: DEFAULT_CURRENCY)
        private set

    /** Printed at the top of exported statements. */
    var businessName by mutableStateOf(prefs.getString(KEY_BUSINESS, "") ?: "")
        private set

    /** Package names of the banking / payment apps whose notifications are read. */
    var watchedApps by mutableStateOf(prefs.getStringSet(KEY_WATCHED_APPS, emptySet())?.toSet() ?: emptySet())
        private set

    /** Also turn "you paid …" notifications into expenses (incomes are always detected). */
    var detectExpenses by mutableStateOf(prefs.getBoolean(KEY_DETECT_EXPENSES, false))
        private set

    /** Add detected money straight away instead of waiting for the user to confirm. */
    var autoAddDetected by mutableStateOf(prefs.getBoolean(KEY_AUTO_ADD, false))
        private set

    /**
     * Whether exchange rates may be downloaded: null until the user has been asked. Only the
     * currency codes and dates are sent, never entries.
     */
    var onlineRates by mutableStateOf(
        if (prefs.contains(KEY_ONLINE_RATES)) prefs.getBoolean(KEY_ONLINE_RATES, false) else null
    )
        private set

    fun updateOnlineRates(enabled: Boolean) {
        onlineRates = enabled
        prefs.edit().putBoolean(KEY_ONLINE_RATES, enabled).apply()
    }

    /** Welcome screens finished or skipped. */
    var onboardingDone by mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
        private set

    fun updateOnboardingDone(done: Boolean) {
        onboardingDone = done
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
    }

    /**
     * The user read what notification access is used for and agreed (Google Play's "prominent
     * disclosure"); asked before Android's own notification access page is opened.
     */
    var notificationConsent by mutableStateOf(prefs.getBoolean(KEY_NOTIFICATION_CONSENT, false))
        private set

    fun updateNotificationConsent(agreed: Boolean) {
        notificationConsent = agreed
        prefs.edit().putBoolean(KEY_NOTIFICATION_CONSENT, agreed).apply()
    }

    /** One-time tips the user closed. */
    var seenTips by mutableStateOf(prefs.getStringSet(KEY_SEEN_TIPS, emptySet())?.toSet() ?: emptySet())
        private set

    fun dismissTip(id: String) {
        seenTips = seenTips + id
        prefs.edit().putStringSet(KEY_SEEN_TIPS, seenTips).apply()
    }

    fun resetTips() {
        seenTips = emptySet()
        prefs.edit().remove(KEY_SEEN_TIPS).apply()
    }

    private val _activeProfileId = MutableStateFlow(prefs.getLong(KEY_ACTIVE_PROFILE, Profile.DEFAULT_ID))
    /** Profile shown on the home screen and used for new entries. */
    val activeProfileId: StateFlow<Long> = _activeProfileId.asStateFlow()

    fun setActiveProfile(id: Long) {
        _activeProfileId.value = id
        prefs.edit().putLong(KEY_ACTIVE_PROFILE, id).apply()
    }

    fun update(currency: String, businessName: String) {
        this.currency = currency.trim().uppercase().ifBlank { DEFAULT_CURRENCY }
        this.businessName = businessName.trim()
        prefs.edit()
            .putString(KEY_CURRENCY, this.currency)
            .putString(KEY_BUSINESS, this.businessName)
            .apply()
    }

    fun updateWatchedApps(packages: Set<String>) {
        watchedApps = packages.toSet()
        prefs.edit().putStringSet(KEY_WATCHED_APPS, watchedApps).apply()
    }

    fun updateDetectExpenses(enabled: Boolean) {
        detectExpenses = enabled
        prefs.edit().putBoolean(KEY_DETECT_EXPENSES, enabled).apply()
    }

    fun updateAutoAdd(enabled: Boolean) {
        autoAddDetected = enabled
        prefs.edit().putBoolean(KEY_AUTO_ADD, enabled).apply()
    }

    private companion object {
        const val KEY_CURRENCY = "currency"
        const val KEY_BUSINESS = "business"
        const val KEY_WATCHED_APPS = "watched_apps"
        const val KEY_DETECT_EXPENSES = "detect_expenses"
        const val KEY_AUTO_ADD = "auto_add_detected"
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val KEY_ONLINE_RATES = "online_rates"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_NOTIFICATION_CONSENT = "notification_consent"
        const val KEY_SEEN_TIPS = "seen_tips"
        const val DEFAULT_CURRENCY = "LKR"
    }
}
