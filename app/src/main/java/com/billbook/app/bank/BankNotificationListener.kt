package com.billbook.app.bank

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.billbook.app.BillBookApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives every notification once the user grants "Notification access", and looks only at
 * those from the banking apps the user picked. Nothing leaves the device.
 */
class BankNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        // Pick up bank notifications that are still in the shade (e.g. arrived before access
        // was granted, or while the app was being updated). Duplicates are filtered out.
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            null
        }
        active?.forEach { handle(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    /** Never throws: a bad notification must not crash the app. */
    private fun handle(sbn: StatusBarNotification) {
        try {
            val app = application as BillBookApp
            val settings = app.settings
            if (sbn.packageName !in settings.watchedApps) return

            val notification = sbn.notification ?: return
            if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

            val extras = notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (lastMessage(extras)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return

            val alert = BankNotificationParser.parse(title, text, settings.currency, settings.detectExpenses) ?: return
            scope.launch {
                try {
                    app.bankAlerts.record(sbn.packageName, title, text, sbn.postTime, alert)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not save detected payment", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read notification from ${sbn.packageName}", e)
        }
    }

    /** SMS / chat style notifications keep the newest message in EXTRA_MESSAGES. */
    @Suppress("DEPRECATION")
    private fun lastMessage(extras: Bundle): CharSequence? =
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.lastOrNull()
            ?.let { (it as? Bundle)?.getCharSequence("text") }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BankNotifications"
    }
}
