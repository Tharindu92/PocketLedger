package com.billbook.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class DetectionStatus { PENDING, ADDED, DISMISSED }

/** Money movement read from a banking app's notification, waiting for the user to confirm. */
@Entity(tableName = "detected_transactions", indices = [Index("status")])
data class DetectedTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val type: TxType,
    val amountCents: Long,
    val currency: String,
    val party: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val status: DetectionStatus = DetectionStatus.PENDING,
    /** The entry created from this detection, once added. */
    val transactionId: Long? = null,
)

@Dao
interface DetectedDao {
    @Query("SELECT * FROM detected_transactions WHERE status = 'PENDING' ORDER BY postedAt DESC")
    fun observePending(): Flow<List<DetectedTransaction>>

    @Query("SELECT COUNT(*) FROM detected_transactions WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM detected_transactions WHERE id = :id")
    suspend fun get(id: Long): DetectedTransaction?

    @Insert
    suspend fun insert(detected: DetectedTransaction): Long

    @Query("UPDATE detected_transactions SET status = :status, transactionId = :transactionId WHERE id = :id")
    suspend fun setStatus(id: Long, status: DetectionStatus, transactionId: Long?)

    /** Used to ignore the same notification being re-posted or updated. */
    @Query(
        "SELECT COUNT(*) FROM detected_transactions WHERE packageName = :packageName " +
            "AND title = :title AND text = :text AND postedAt > :since"
    )
    suspend fun countSimilarSince(packageName: String, title: String, text: String, since: Long): Int
}
