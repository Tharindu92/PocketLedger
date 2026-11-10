package com.billbook.app.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Transaction::class, DetectedTransaction::class, Profile::class, ExchangeRate::class, CategoryRule::class],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun detected(): DetectedDao
    abstract fun profiles(): ProfileDao
    abstract fun rates(): ExchangeRateDao
    abstract fun categoryRules(): CategoryRuleDao

    companion object {
        /** [defaultCurrency] is used to fill in the currency of entries created before v2. */
        fun build(context: Context, defaultCurrency: () -> String): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "billbook.db")
                .addMigrations(migration1to2(defaultCurrency))
                .addCallback(object : Callback() {
                    // Make sure the default "Personal" profile exists (new installs and upgrades).
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT OR IGNORE INTO profiles (id, name) VALUES (${Profile.DEFAULT_ID}, 'Personal')")
                    }
                })
                .build()

        private fun migration1to2(defaultCurrency: () -> String) = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN currency TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE transactions SET currency = ?", arrayOf<Any?>(defaultCurrency()))
            }
        }
    }
}
