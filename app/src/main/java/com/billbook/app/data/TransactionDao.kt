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

    @Upsert
    suspend fun upsert(transaction: Transaction): Long

    @Delete
    suspend fun delete(transaction: Transaction)
}
