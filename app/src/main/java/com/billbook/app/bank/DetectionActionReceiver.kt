package com.billbook.app.bank

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.billbook.app.BillBookApp
import com.billbook.app.data.DetectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the profile / Ignore buttons on a "Money received" notification. */
class DetectionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val detectionId = intent.getLongExtra(EXTRA_DETECTION_ID, 0L)
        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, DetectionNotifier.IGNORE)
        if (detectionId <= 0) return

        val app = context.applicationContext as BillBookApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val detection = app.bankAlerts.get(detectionId)
                if (detection != null && detection.status == DetectionStatus.PENDING) {
                    if (profileId == DetectionNotifier.IGNORE) app.bankAlerts.dismiss(detectionId)
                    else app.bankAlerts.add(detection, profileId)
                }
                DetectionNotifier.cancel(app, detectionId)
            } catch (e: Exception) {
                Log.e("BankNotifications", "Could not handle notification action", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_DETECTION_ID = "detection_id"
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
