package com.billbook.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

object Money {
    /** Parses user input such as "1,250.5" into cents. Returns null when it is not a number. */
    fun parseToCents(input: String): Long? {
        val cleaned = input.trim().replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        return runCatching {
            value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    /** Plain editable form, e.g. 125050 -> "1250.50". */
    fun toInput(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** Display form with grouping, e.g. 125050 -> "1,250.50". */
    fun format(cents: Long): String {
        val nf = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return nf.format(BigDecimal.valueOf(cents, 2))
    }

    fun format(cents: Long, currency: String): String = "$currency ${format(cents)}"
}
