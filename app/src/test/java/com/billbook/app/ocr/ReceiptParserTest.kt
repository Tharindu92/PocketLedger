package com.billbook.app.ocr

import com.billbook.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {

    private val today = LocalDate.of(2026, 11, 8)

    @Test
    fun supermarketReceipt() {
        val text = """
            KEELLS SUPER
            No. 123, Galle Road, Colombo 03
            Tel: 011 2345678
            Date: 05/11/2026 Time: 18:42
            Bill No: 004512
            Cashier: Nimal
            Milk Powder 400g        1,150.00
            Bread                     180.00
            Eggs x10                  650.00
            Sub Total               1,980.00
            VAT 18%                   356.40
            TOTAL                   2,336.40
            CASH                    3,000.00
            CHANGE                    663.60
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today)

        assertEquals(233640L, r.totalCents)
        assertEquals(35640L, r.taxCents)
        assertEquals("004512", r.billNumber)
        assertEquals("KEELLS SUPER", r.merchant)
        assertEquals(LocalDate.of(2026, 11, 5), r.date)
        assertEquals("Cash", r.paymentMethod)
        assertEquals("Groceries", r.category)
    }

    @Test
    fun serviceInvoice() {
        val text = """
            TAX INVOICE
            Lanka Tech Solutions (Pvt) Ltd
            Invoice No: INV-2026-0042
            Invoice Date: 2026-10-28
            Description            Amount
            Web hosting (annual)  45,000.00
            Sub-total             45,000.00
            VAT (18%)              8,100.00
            Grand Total           53,100.00
            Payment: Bank Transfer
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today)

        assertEquals(5310000L, r.totalCents)
        assertEquals(810000L, r.taxCents)
        assertEquals("INV-2026-0042", r.billNumber)
        assertEquals("Lanka Tech Solutions (Pvt) Ltd", r.merchant)
        assertEquals(LocalDate.of(2026, 10, 28), r.date)
        assertEquals("Bank transfer", r.paymentMethod)
        assertEquals("Software & Subscriptions", r.category)
    }

    @Test
    fun totalOnNextLineAndTextMonth() {
        val text = """
            Cafe Mocha
            12 Mar 2026
            TOTAL
            Rs. 1,450.00
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today)

        assertEquals(145000L, r.totalCents)
        assertEquals("Cafe Mocha", r.merchant)
        assertEquals(LocalDate.of(2026, 3, 12), r.date)
        assertEquals("Dining", r.category)
        assertNull(r.billNumber)
    }

    @Test
    fun labelAndValueInSeparateOcrBlocksAreJoinedIntoOneRow() {
        val lines = listOf(
            OcrLine("TOTAL", 10, 100, 80, 120),
            OcrLine("CASH", 10, 130, 70, 150),
            OcrLine("2,500.00", 300, 102, 380, 121),
            OcrLine("3,000.00", 300, 131, 380, 151),
        )
        assertEquals(listOf("TOTAL  2,500.00", "CASH  3,000.00"), ReceiptParser.groupIntoRows(lines))
        assertEquals(250000L, ReceiptParser.parse(lines, today).totalCents)
    }

    @Test
    fun amountsIgnoreDatesAndPercentages() {
        assertEquals(emptyList<Long>(), ReceiptParser.amountsIn("Date 12.05.24 VAT 18.00%"))
        assertEquals(listOf(125000L), ReceiptParser.amountsIn("Rs.1,250.00"))
    }

    @Test
    fun currencyDetection() {
        fun detect(text: String, home: String = "LKR") = ReceiptParser.parseText(text, today, home).currency

        assertEquals("LKR", detect("Bill\nTOTAL (LKR)   2,500.00"))
        assertEquals("USD", detect("Coffee House\nTotal: $45.00", home = "LKR"))
        assertEquals("AUD", detect("Coffee House\nTotal: $45.00", home = "AUD"))
        assertEquals("SGD", detect("TOTAL S$ 12.00"))
        assertEquals("USD", detect("TOTAL US$ 12.00", home = "SGD"))
        assertEquals("INR", detect("Grand Total ₹ 500.00"))
        assertEquals("EUR", detect("Summe € 9,50"))
        assertEquals("GBP", detect("TOTAL £12.40"))
        assertEquals("LKR", detect("TOTAL Rs. 1,450.00", home = "USD"))
        assertEquals("INR", detect("TOTAL Rs. 1,450.00", home = "INR"))
        assertEquals("MYR", detect("JUMLAH RM 23.90"))
        assertEquals("LKR", detect("SLR 300.00 paid"))
        // Codes beat symbols; no marker at all -> not detected.
        assertEquals("EUR", detect("Total EUR 10.00 ($11.00)"))
        assertNull(detect("TOTAL 2,336.40"))
        assertEquals(950L, ReceiptParser.parseText("Summe € 9,50", today).totalCents)
    }

    @Test
    fun moneyParsing() {
        assertEquals(125050L, Money.parseToCents("1,250.5"))
        assertNull(Money.parseToCents("abc"))
        assertEquals("1,250.50", Money.format(125050))
    }
}
