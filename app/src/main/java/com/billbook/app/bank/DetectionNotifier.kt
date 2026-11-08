package com.billbook.app.bank

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.billbook.app.MainActivity
import com.billbook.app.R
import com.billbook.app.data.DetectedTransaction
import com.billbook.app.data.Profile
import com.billbook.app.data.TxType
import com.billbook.app.util.Money

/**
 * Shows "Money received: + EUR 2.00 … [Personal] [Club] [Ignore]" so the user can file a
 * detected payment into the right profile straight from the notification shade.
 */
object DetectionNotifier {
    private const val CHANNEL_ID = "detected_payments"
    private const val TAG = "detected"
    const val IGNORE = -1L

    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission") // checked in canPost()
    fun show(context: Context, d: DetectedTransaction, profiles: List<Profile>) {
        if (!canPost(context) || profiles.isEmpty()) return
        ensureChannel(context)

        val income = d.type == TxType.INCOME
        val amount = (if (income) "+ " else "− ") + Money.format(d.amountCents, d.currency)
        val title = if (income) "Money received: $amount" else "Payment: $amount"
        val who = listOf(d.party, d.appLabel).filter { it.isNotBlank() }.joinToString(" · ")
        val question = if (profiles.size > 1) "Which profile does it belong to?" else "Add it to Pocket Ledger?"

        val openApp = PendingIntent.getActivity(
            context,
            d.id.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BANK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_billbook)
            .setContentTitle(title)
            .setContentText("$who — $question")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$who\n$question"))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // Android shows at most 3 action buttons.
        if (profiles.size == 1) {
            builder.addAction(0, "Add", actionIntent(context, d.id, 0, profiles.first().id))
            builder.addAction(0, "Ignore", actionIntent(context, d.id, 9, IGNORE))
        } else if (profiles.size == 2) {
            profiles.forEachIndexed { i, p -> builder.addAction(0, p.name, actionIntent(context, d.id, i, p.id)) }
            builder.addAction(0, "Ignore", actionIntent(context, d.id, 9, IGNORE))
        } else {
            // More profiles than buttons: offer the first three; the rest are in the app (tap the notification).
            profiles.take(3).forEachIndexed { i, p -> builder.addAction(0, p.name, actionIntent(context, d.id, i, p.id)) }
        }

        NotificationManagerCompat.from(context).notify(TAG, d.id.toInt(), builder.build())
    }

    fun cancel(context: Context, detectionId: Long) =
        NotificationManagerCompat.from(context).cancel(TAG, detectionId.toInt())

    private fun actionIntent(context: Context, detectionId: Long, slot: Int, profileId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (detectionId * 10 + slot).toInt(),
            Intent(context, DetectionActionReceiver::class.java)
                .putExtra(DetectionActionReceiver.EXTRA_DETECTION_ID, detectionId)
                .putExtra(DetectionActionReceiver.EXTRA_PROFILE_ID, profileId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, "Detected bank payments", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Asks which profile a payment from your banking app belongs to" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
