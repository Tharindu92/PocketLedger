package com.billbook.app.bank

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    /** Name or package suggests a bank / payment / wallet app. */
    val looksFinancial: Boolean,
)

object InstalledApps {

    private val FINANCE_HINTS = Regex(
        "(?i)bank|banking|pay|wallet|money|finance|cash|n26|revolut|wise|transferwise|paypal|venmo|" +
            "sparkasse|volksbank|commerzbank|deutsche|dkb|comdirect|\\bing\\b|postbank|bunq|monzo|starling|" +
            "sampath|hnb|combank|commercial|boc|peoples|nations|ndb|seylan|dfcc|pan ?asia|amana|" +
            "genie|frimi|ezcash|mcash|lankaqr|upi|paytm|phonepe|gpay|vivid|trade republic|klarna"
    )

    /** Apps that appear in the launcher (visible to us through the <queries> launcher intent). */
    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return activities
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(pm).toString()
                InstalledApp(
                    packageName = pkg,
                    label = label,
                    icon = runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                    looksFinancial = FINANCE_HINTS.containsMatchIn(label) || FINANCE_HINTS.containsMatchIn(pkg),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    fun isNotificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun notificationAccessSettings(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appInfoSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("unused")
    fun listenerComponent(context: Context) = ComponentName(context, BankNotificationListener::class.java)
}
