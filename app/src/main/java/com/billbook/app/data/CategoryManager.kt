package com.billbook.app.data

import androidx.room.withTransaction

/** Adds, renames (or merges) and hides categories, keeping entries and rules consistent. */
class CategoryManager(private val db: AppDatabase) {
    private val settings = db.categorySettings()

    /** Cleans up a typed name: trimmed, single spaces, first letter capital. */
    fun clean(name: String): String =
        name.trim().replace(Regex("""\s+"""), " ").replaceFirstChar { it.uppercase() }

    /** Adds [name] to the lists for [type]; un-hides it when it was a hidden built-in. Returns the clean name. */
    suspend fun add(name: String, type: TxType): String {
        val clean = clean(name)
        if (clean.isEmpty()) return clean
        if (CategoryCatalog.isBuiltIn(clean, type)) settings.delete(clean, type)
        else settings.upsert(CategorySetting(clean, type))
        return clean
    }

    suspend fun setHidden(name: String, type: TxType, hidden: Boolean) {
        if (CategoryCatalog.isLocked(name)) return
        when {
            hidden -> settings.upsert(CategorySetting(name, type, hidden = true))
            CategoryCatalog.isBuiltIn(name, type) -> settings.delete(name, type)
            else -> settings.upsert(CategorySetting(name, type, hidden = false))
        }
    }

    /**
     * Renames [from] to [to] for [type] on every entry and rule. When [to] already exists this
     * merges the two. A renamed built-in is hidden, since entries no longer use it. Returns how
     * many entries changed.
     */
    suspend fun rename(from: String, to: String, type: TxType): Int {
        val target = clean(to)
        if (target.isEmpty() || target == from || CategoryCatalog.isLocked(from)) return 0
        return db.withTransaction {
            val changed = db.transactions().renameCategory(type, from, target)
            db.categoryRules().renameCategory(type, from, target)
            if (CategoryCatalog.isBuiltIn(from, type)) settings.upsert(CategorySetting(from, type, hidden = true))
            else settings.delete(from, type)
            add(target, type)
            changed
        }
    }

    /** Removes an added category that no entry uses. */
    suspend fun delete(name: String, type: TxType) {
        if (!CategoryCatalog.isBuiltIn(name, type)) settings.delete(name, type)
    }
}
