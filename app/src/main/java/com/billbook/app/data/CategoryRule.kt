package com.billbook.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * "Entries of [type] whose payee or description contains [keyword] get [category]", saved when the
 * user agrees to remember a choice. Rules beat every other guess. [keyword] is stored lowercase.
 */
@Entity(tableName = "category_rules", primaryKeys = ["keyword", "type"])
data class CategoryRule(
    val keyword: String,
    val type: TxType,
    val category: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface CategoryRuleDao {
    @Query("SELECT * FROM category_rules ORDER BY keyword")
    fun observeAll(): Flow<List<CategoryRule>>

    @Query("SELECT * FROM category_rules")
    suspend fun all(): List<CategoryRule>

    @Upsert
    suspend fun upsert(rule: CategoryRule)

    @Query("UPDATE category_rules SET category = :to WHERE type = :type AND category = :from")
    suspend fun renameCategory(type: TxType, from: String, to: String)

    @Query("DELETE FROM category_rules WHERE keyword = :keyword AND type = :type")
    suspend fun delete(keyword: String, type: TxType)
}
