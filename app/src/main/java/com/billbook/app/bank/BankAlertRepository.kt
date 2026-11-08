package com.billbook.app.bank

import com.billbook.app.BillBookApp
import com.billbook.app.data.DetectedTransaction
import com.billbook.app.data.DetectionStatus
import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import java.time.Instant
import java.time.ZoneId

/** Stores money detected in notifications and turns confirmed detections into entries. */
class BankAlertRepository(private val app: BillBookApp) {
    private val detected get() = app.database.detected()
    private val transactions get() = app.database.transactions()
    private val profiles get() = app.database.profiles()

    suspend fun record(packageName: String, title: String, text: String, postedAt: Long, alert: BankAlert) {
        // Apps often re-post or update the same notification; keep only one copy.
        if (detected.countSimilarSince(packageName, title, text, postedAt - DUPLICATE_WINDOW_MS) > 0) return

        val detection = DetectedTransaction(
            packageName = packageName,
            appLabel = InstalledApps.label(app, packageName),
            type = alert.type,
            amountCents = alert.amountCents,
            currency = alert.currency ?: app.settings.currency,
            party = alert.party.orEmpty(),
            title = title,
            text = text,
            postedAt = postedAt,
        )
        val saved = detection.copy(id = detected.insert(detection))
        val allProfiles = profiles.all()

        // Auto-add only makes sense with a single profile; otherwise the user picks one.
        if (app.settings.autoAddDetected && allProfiles.size == 1) {
            add(saved, allProfiles.first().id)
        } else {
            DetectionNotifier.show(app, saved, allProfiles)
        }
    }

    /** Adds the detection as an entry in [profileId]. Returns the new entry id. */
    suspend fun add(detection: DetectedTransaction, profileId: Long): Long {
        val id = transactions.upsert(toTransaction(detection, profileId))
        detected.setStatus(detection.id, DetectionStatus.ADDED, id)
        DetectionNotifier.cancel(app, detection.id)
        return id
    }

    suspend fun markAdded(detectionId: Long, transactionId: Long) {
        detected.setStatus(detectionId, DetectionStatus.ADDED, transactionId)
        DetectionNotifier.cancel(app, detectionId)
    }

    suspend fun dismiss(detectionId: Long) {
        detected.setStatus(detectionId, DetectionStatus.DISMISSED, null)
        DetectionNotifier.cancel(app, detectionId)
    }

    suspend fun get(detectionId: Long) = detected.get(detectionId)

    fun toTransaction(d: DetectedTransaction, profileId: Long) = Transaction(
        profileId = profileId,
        type = d.type,
        amountCents = d.amountCents,
        currency = d.currency,
        dateEpochDay = Instant.ofEpochMilli(d.postedAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(),
        party = d.party,
        category = if (d.type == TxType.INCOME) "Other income" else "",
        paymentMethod = if (d.type == TxType.INCOME) "Bank transfer" else "Card",
        notes = "From ${d.appLabel} notification: ${listOf(d.title, d.text).filter { it.isNotBlank() }.joinToString(" – ")}",
    )

    private companion object {
        const val DUPLICATE_WINDOW_MS = 10 * 60 * 1000L
    }
}
