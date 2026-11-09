package com.billbook.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 1 [base] = [rate] [quote] on [day] (epoch day), e.g. 1 EUR = 369.94 LKR. Manual rates typed by
 * the user are stored with [day] = [ANY_DAY] and apply to every date.
 */
@Entity(tableName = "exchange_rates", primaryKeys = ["base", "quote", "day"])
data class ExchangeRate(
    val base: String,
    val quote: String,
    val day: Long,
    val rate: Double,
    val manual: Boolean = false,
) {
    companion object {
        const val ANY_DAY = -1L
    }
}

@Dao
interface ExchangeRateDao {
    @Query("SELECT * FROM exchange_rates WHERE base = :base")
    fun observeForBase(base: String): Flow<List<ExchangeRate>>

    @Query("SELECT * FROM exchange_rates WHERE base = :base")
    suspend fun forBase(base: String): List<ExchangeRate>

    @Upsert
    suspend fun upsertAll(rates: List<ExchangeRate>)
}
