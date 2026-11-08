package com.billbook.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class TxType { INCOME, EXPENSE }

/**
 * One income or expense entry. Money is stored in minor units (cents) to avoid
 * floating-point rounding errors in totals.
 */
@Entity(tableName = "transactions", indices = [Index("dateEpochDay"), Index("profileId")])
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which [Profile] (Personal, Club, …) this entry belongs to. */
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profile.DEFAULT_ID,
    val type: TxType,
    val amountCents: Long,
    /** ISO 4217 code, e.g. "LKR", "USD". */
    val currency: String = "",
    val taxCents: Long = 0,
    /** Bill / transaction date as [LocalDate.toEpochDay]. */
    val dateEpochDay: Long,
    /** Shop / business for expenses, payer for income. */
    val party: String = "",
    val billNumber: String = "",
    val category: String = "",
    val paymentMethod: String = "",
    val notes: String = "",
    /** Absolute path of the bill photo stored in the app's private files dir. */
    val imagePath: String? = null,
    /** Raw text read from the bill photo, kept for reference. */
    val ocrText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

val Transaction.date: LocalDate get() = LocalDate.ofEpochDay(dateEpochDay)

val Transaction.signedCents: Long get() = if (type == TxType.INCOME) amountCents else -amountCents

/** Result row for "which categories has the user already used". */
data class TypeCategory(val type: TxType, val category: String)
