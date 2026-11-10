package com.billbook.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * A category the user added ([hidden] false), or a built-in one they hid from the pick lists
 * ([hidden] true). Built-in categories without a row are simply shown.
 */
@Entity(tableName = "category_settings", primaryKeys = ["name", "type"])
data class CategorySetting(
    val name: String,
    val type: TxType,
    val hidden: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface CategorySettingDao {
    @Query("SELECT * FROM category_settings ORDER BY name")
    fun observeAll(): Flow<List<CategorySetting>>

    @Upsert
    suspend fun upsert(setting: CategorySetting)

    @Query("DELETE FROM category_settings WHERE name = :name AND type = :type")
    suspend fun delete(name: String, type: TxType)
}

data class CategoryCount(val type: TxType, val category: String, val count: Int)

/** Which categories to offer, per type. Pure, so the rules are easy to test. */
object CategoryCatalog {

    /** Can't be renamed or hidden: Insights counts it as saving. */
    fun isLocked(name: String) = name == Categories.INVESTMENTS

    fun isBuiltIn(name: String, type: TxType) = name in Categories.defaultsFor(type)

    /** Built-in ones first (in their usual order), then added and used ones A–Z; hidden ones left out. */
    fun list(type: TxType, settings: List<CategorySetting>, used: List<String>): List<String> {
        val hidden = settings.filter { it.type == type && it.hidden }.map { it.name }.toSet()
        val added = settings.filter { it.type == type && !it.hidden }.map { it.name }
        val extra = (added + used).map { it.trim() }.filter { it.isNotEmpty() && !isBuiltIn(it, type) }
            .distinct().sortedBy { it.lowercase() }
        return (Categories.defaultsFor(type) + extra).filter { it !in hidden }
    }

    /** Pick lists for both types, kept up to date. */
    fun observe(db: AppDatabase): Flow<Map<TxType, List<String>>> =
        combine(db.categorySettings().observeAll(), db.transactions().observeUsedCategories()) { settings, used ->
            TxType.entries.associateWith { type ->
                list(type, settings, used.filter { it.type == type }.map { it.category })
            }
        }
}
