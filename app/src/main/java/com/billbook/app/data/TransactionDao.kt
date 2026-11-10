package com.billbook.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query(
        "SELECT * FROM transactions WHERE profileId = :profileId AND dateEpochDay BETWEEN :fromDay AND :toDay " +
            "ORDER BY dateEpochDay DESC, id DESC"
    )
    fun observeBetween(profileId: Long, fromDay: Long, toDay: Long): Flow<List<Transaction>>

    @Query(
        "SELECT * FROM transactions WHERE profileId = :profileId AND dateEpochDay BETWEEN :fromDay AND :toDay " +
            "ORDER BY dateEpochDay ASC, id ASC"
    )
    suspend fun listBetween(profileId: Long, fromDay: Long, toDay: Long): List<Transaction>

    @Query(
        "SELECT * FROM transactions WHERE dateEpochDay BETWEEN :fromDay AND :toDay " +
            "ORDER BY dateEpochDay DESC, id DESC"
    )
    fun observeAllProfilesBetween(fromDay: Long, toDay: Long): Flow<List<Transaction>>

    /** Day of the oldest entry of a profile, or of all profiles when [profileId] is null. */
    @Query("SELECT MIN(dateEpochDay) FROM transactions WHERE :profileId IS NULL OR profileId = :profileId")
    fun observeFirstDay(profileId: Long?): Flow<Long?>

    /** Entries of every profile in a date range, for spotting bookings that are already recorded. */
    @Query("SELECT * FROM transactions WHERE dateEpochDay BETWEEN :fromDay AND :toDay")
    suspend fun allBetween(fromDay: Long, toDay: Long): List<Transaction>

    @Upsert
    suspend fun upsertAll(transactions: List<Transaction>)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): Transaction?

    @Query("SELECT DISTINCT type, category FROM transactions WHERE category != '' ORDER BY category")
    fun observeUsedCategories(): Flow<List<TypeCategory>>

    /** Business / payer names used before, most recent first, for recognising them in scans. */
    @Query(
        "SELECT party FROM transactions WHERE party != '' GROUP BY party " +
            "ORDER BY MAX(createdAt) DESC LIMIT 500"
    )
    suspend fun knownParties(): List<String>

    /** Payee and category of every categorised entry, for learning which payee gets which category. */
    @Query("SELECT type, party, category FROM transactions WHERE party != '' AND category != ''")
    suspend fun partyCategories(): List<PartyCategory>

    @Query("SELECT type, category, COUNT(*) AS count FROM transactions WHERE category != '' GROUP BY type, category")
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    /** Moves every entry of [type] from category [from] to [to]; returns how many changed. */
    @Query("UPDATE transactions SET category = :to WHERE type = :type AND category = :from")
    suspend fun renameCategory(type: TxType, from: String, to: String): Int

    @Upsert
    suspend fun upsert(transaction: Transaction): Long

    @Delete
    suspend fun delete(transaction: Transaction)

    @Delete
    suspend fun deleteAll(transactions: List<Transaction>)
}
