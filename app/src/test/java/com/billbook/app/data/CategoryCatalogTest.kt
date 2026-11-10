package com.billbook.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryCatalogTest {

    @Test
    fun builtInFirstThenAddedAndUsedAlphabetically() {
        val settings = listOf(
            CategorySetting("Pets", TxType.EXPENSE),
            CategorySetting("Marketing", TxType.EXPENSE, hidden = true),
            CategorySetting("Side project", TxType.INCOME),
        )
        val list = CategoryCatalog.list(TxType.EXPENSE, settings, used = listOf("Kids", "Groceries", " "))
        assertEquals(Categories.expense.filter { it != "Marketing" } + listOf("Kids", "Pets"), list)
        assertFalse("Side project" in list)
    }

    @Test
    fun hiddenAddedCategoryIsLeftOutEvenWhenUsed() {
        val settings = listOf(CategorySetting("Kids", TxType.EXPENSE, hidden = true))
        assertFalse("Kids" in CategoryCatalog.list(TxType.EXPENSE, settings, used = listOf("Kids")))
    }

    @Test
    fun investmentsIsLocked() {
        assertTrue(CategoryCatalog.isLocked(Categories.INVESTMENTS))
        assertFalse(CategoryCatalog.isLocked("Groceries"))
        assertTrue(CategoryCatalog.isBuiltIn("Dividends", TxType.INCOME))
        assertFalse(CategoryCatalog.isBuiltIn("Dividends", TxType.EXPENSE))
    }
}
