package com.billbook.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvReaderTest {

    @Test
    fun readsQuotedCellsAndBom() {
        val csv = "﻿Date,Business,Amount,Notes\r\n" +
            "2026-10-28,\"Department for Registration of Persons\",2500.00,\"one-day, renewal\"\r\n" +
            "2026-09-27,Pizza Hut München,38.20,\"said \"\"thanks\"\"\"\r\n"

        val rows = CsvReader.parse(csv)

        assertEquals(3, rows.size)
        assertEquals(listOf("Date", "Business", "Amount", "Notes"), rows[0])
        assertEquals("one-day, renewal", rows[1][3])
        assertEquals("Pizza Hut München", rows[2][1])
        assertEquals("said \"thanks\"", rows[2][3])
    }

    @Test
    fun keepsEmptyCellsAndLastLineWithoutNewline() {
        assertEquals(listOf(listOf("a", "", "c"), listOf("d", "e", "")), CsvReader.parse("a,,c\nd,e,"))
    }
}
