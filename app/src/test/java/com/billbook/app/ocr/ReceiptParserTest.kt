package com.billbook.app.ocr

import com.billbook.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // Real ML Kit output for two photos of a Sri Lankan government receipt lying on a newspaper
    // (personal values replaced). Newspaper text surrounds the receipt, the receipt number sits
    // under its label with a stray speck ("d") in between, the date lost both slashes
    // ("20261028 09:05") and in photo B the total "2,500.00" was read as "2.500.00".
    private val photoA = listOf(
        OcrLine("hed eftects of sus-", 408, -6, 1021, 116),
        OcrLine("conomicowth and targeted wel", 6, 22, 985, 187),
        OcrLine("Iventio, Desnie", 12, 109, 576, 212),
        OcrLine("bolarisat", 6, 189, 268, 271),
        OcrLine("ensior", -2, 258, 201, 341),
        OcrLine("devel", 4, 344, 169, 415),
        OcrLine("bgnis", -5, 415, 158, 490),
        OcrLine("uct", 42, 494, 132, 566),
        OcrLine("d", 31, 724, 60, 786),
        OcrLine("rea", 42, 1763, 149, 1818),
        OcrLine("tal", 70, 2089, 159, 2155),
        OcrLine("Receipt", 394, 266, 696, 350),
        OcrLine("App. No.", 389, 624, 711, 772),
        OcrLine("Name", 326, 888, 562, 956),
        OcrLine("DEPARTMENT FOR REGISTRATION OF PERSONS", 621, 302, 2574, 488),
        OcrLine("DRP Ref No.", 302, 1104, 886, 1202),
        OcrLine("Payment Mode:", 336, 1360, 979, 1464),
        OcrLine("# Description", 388, 1592, 941, 1683),
        OcrLine("Shroff:", 270, 2894, 528, 2971),
        OcrLine("DRPENIdA1B23C45", 1024, 753, 1860, 853),
        OcrLine("PERERA A.", 1036, 905, 1341, 996),
        OcrLine("26HINO123456", 1008, 1220, 1601, 1310),
        OcrLine("ONE DAY SERVICE FEE", 496, 1776, 1436, 1878),
        OcrLine("RENEW APPLICAION FEE", 504, 1930, 1586, 2027),
        OcrLine("C1000", 549, 2957, 783, 3030),
        OcrLine("CASH", 1040, 1446, 1264, 1510),
        OcrLine("Received with thanks a sum of", 286, 2581, 1420, 2701),
        OcrLine("Hundred Rupees Only.", 401, 2725, 1300, 2842),
        OcrLine("Receipt No.:", 1675, 599, 2332, 728),
        OcrLine("12600000123", 2044, 779, 2554, 877),
        OcrLine("Receipt Date", 1622, 1119, 2175, 1226),
        OcrLine("Total", 1757, 2478, 1961, 2554),
        OcrLine("20261028 09:05", 2046, 1235, 2696, 1319),
        OcrLine("286", 2151, 1398, 2367, 1486),
        OcrLine("Amount", 2207, 1621, 2535, 1693),
        OcrLine("2.000.00", 2418, 1820, 2754, 1892),
        OcrLine("500.00", 2494, 1972, 2758, 2044),
        OcrLine("2,500.00", 2427, 2514, 2773, 2600),
        OcrLine("Two Thousand Flve", 1824, 2665, 2596, 2747),
        OcrLine("3100000", 1950, 2812, 2615, 2968),
        OcrLine("Please quote above Application Number when making inquiries.", 424, 3081, 2577, 3239),
        OcrLine("This is a computer generated receipt & requires no signatures", 461, 3184, 2534, 3343),
        OcrLine("Contact: (011) 5226126", 280, 3298, 1083, 3433),
        OcrLine("Department of Government", 3025, 1583, 3063, 1944),
    )

    private val photoB = listOf(
        OcrLine("as Wellas the Generl co", 4, -2, 827, 74),
        OcrLine("lctes communities thar", 25, 61, 811, 155),
        OcrLine("these classifications. per ceht for teneral category. Over thne", 9, -40, 2444, 279),
        OcrLine("T community had the", 70, 222, 764, 306),
        OcrLine("Receipt", 299, 406, 618, 495),
        OcrLine("J App. No.", 41, 792, 627, 957),
        OcrLine("Name", 247, 1047, 495, 1115),
        OcrLine("DRP Ref. No.", 231, 1286, 769, 1368),
        OcrLine("DEPARTMENT T FOR REGISTRATION OF PERSONS", 548, 483, 2478, 662),
        OcrLine("Payment Mode:", 223, 1534, 914, 1629),
        OcrLine("atdrOpped t", 957, 33, 1442, 114),
        OcrLine("ighest", 787, 191, 1000, 256),
        OcrLine("mt. foL", 809, 264, 998, 335),
        OcrLine("# Description", 325, 1761, 872, 1853),
        OcrLine("Same period verty among urban STs", 1095, 73, 2453, 261),
        OcrLine("o0 2 Der cent to 9.9 per", 1691, 168, 2454, 269),
        OcrLine("Shroff:", 188, 3061, 443, 3138),
        OcrLine("C1000", 457, 3119, 687, 3206),
        OcrLine("DRPENIdA1B23C45", 960, 932, 1784, 1028),
        OcrLine("PERERA A.", 974, 1068, 1276, 1168),
        OcrLine("26HINOb23456", 940, 1373, 1535, 1477),
        OcrLine("ONE DAY SERVICE FEE", 434, 1948, 1390, 2042),
        OcrLine("CASH", 976, 1613, 1200, 1677),
        OcrLine("RENEW APPLICAION FEE", 442, 2103, 1502, 2179),
        OcrLine("Received with thanks a sum of", 225, 2748, 1338, 2864),
        OcrLine("Hundred Rupees Only.", 332, 2885, 1230, 3017),
        OcrLine("Receipt No.", 1558, 788, 2059, 903),
        OcrLine("12600000123", 1970, 955, 2463, 1054),
        OcrLine("Receipt Date", 1596, 1290, 2118, 1403),
        OcrLine("Total", 1680, 2637, 1895, 2718),
        OcrLine("20261028 09:05", 1931, 1393, 2611, 1488),
        OcrLine("286", 2080, 1565, 2296, 1649),
        OcrLine("Amount", 2142, 1780, 2470, 1853),
        OcrLine("2.000.00", 2358, 1980, 2706, 2048),
        OcrLine("500.00", 2442, 2119, 2714, 2201),
        OcrLine("2.500.00", 2390, 2677, 2766, 2757),
        OcrLine("Two Thousand Fve", 1759, 2823, 2570, 2934),
        OcrLine("3100000", 1878, 2982, 2603, 3157),
        OcrLine("Please quote above Application Number when making inquiries.", 324, 3249, 2575, 3456),
        OcrLine("This is a computer generated receipt & requires no signatures", 355, 3360, 2524, 3570),
        OcrLine("Contact: (011) 5226126", 178, 3483, 959, 3633),
        OcrLine("CM-044274c - Department of Government PTInting", 2967, 1501, 3037, 2213),
    )

    private fun assertGovernmentReceipt(lines: List<OcrLine>, expectedMerchant: String) {
        val r = ReceiptParser.parse(lines, today, homeCurrency = "LKR")
        assertEquals(expectedMerchant, r.merchant)
        assertEquals("12600000123", r.billNumber)
        assertEquals(250000L, r.totalCents)
        assertEquals(LocalDate.of(2026, 10, 28), r.date)
        assertEquals("Cash", r.paymentMethod)
        assertEquals("LKR", r.currency)
        assertEquals("Government & Fees", r.category)
        // The other numbers on the form are offered as one-tap alternatives, receipt no. first.
        assertEquals("12600000123", r.billNumberOptions.first())
        assertTrue(r.billNumberOptions.any { it.startsWith("26HINO") })      // DRP Ref. No.
        assertTrue(r.billNumberOptions.any { it.startsWith("DRPENIdA") })    // App. No.
        assertEquals(250000L, r.totalOptions.first())
        assertEquals(expectedMerchant, r.merchantOptions.first())
    }

    @Test
    fun joinPiecesDropsRepeatedWords() {
        assertEquals(
            "Department of Registration of Persons",
            ReceiptParser.joinPieces("Department of Reg", "Registration of Persons"),
        )
        assertEquals("Department for Registration", ReceiptParser.joinPieces("Department for", "Registration"))
        assertEquals("Blue Moon Cafe", ReceiptParser.joinPieces("Blue Moon", "Moon Cafe"))
        // A one- or two-letter tail is not treated as a cut-off word.
        assertEquals("SHOP A Avenue Road", ReceiptParser.joinPieces("SHOP A", "Avenue Road"))
        assertEquals("Persons", ReceiptParser.joinPieces("", "Persons"))
    }

    /** OCR split the name in two pieces on one line; the joined name is offered right after the best. */
    @Test
    fun splitBusinessNameIsOfferedJoined() {
        val lines = listOf(
            OcrLine("Receipt", 100, 40, 260, 80),
            OcrLine("DEPARTMENT OF REG", 100, 100, 560, 150),
            OcrLine("REGISTRATION OF PERSONS", 600, 102, 1200, 152),
            OcrLine("TOTAL 2,500.00", 100, 400, 700, 450),
        )
        val r = ReceiptParser.parse(lines, today)
        assertEquals("DEPARTMENT OF REGISTRATION OF PERSONS", r.merchantOptions[1])
        assertTrue(r.merchantOptions.containsAll(listOf("DEPARTMENT OF REG", "REGISTRATION OF PERSONS")))
    }

    /** A name wrapped onto two lines of the same size is joined; an address under it is not. */
    @Test
    fun wrappedBusinessNameIsOfferedJoined() {
        val lines = listOf(
            OcrLine("LANKA TECH", 300, 100, 700, 150),
            OcrLine("SOLUTIONS PVT LTD", 250, 158, 760, 208),
            OcrLine("No 12 Galle Road", 280, 216, 730, 266),
        )
        val r = ReceiptParser.parse(lines, today)
        assertTrue("LANKA TECH SOLUTIONS PVT LTD" in r.merchantOptions)
        assertTrue(r.merchantOptions.none { it.contains("Galle") })
    }

    @Test
    fun wordFragmentsAreNotBillNumbers() {
        assertNull(ReceiptParser.parseText("Shop\nReceipt No: 0des", today).billNumber)
        assertEquals("A-104", ReceiptParser.parseText("Shop\nBill No: A-104", today).billNumber)
    }

    @Test
    fun optionsListRunnerUpValues() {
        val r = ReceiptParser.parseText(
            "Cafe Mocha\nDate: 03/11/2026\nPrinted 01/11/2026\nSub Total 900.00\nTOTAL 1,000.00\nCASH 2,000.00",
            today,
        )
        assertEquals(LocalDate.of(2026, 11, 3), r.date)
        assertEquals(listOf(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 1)), r.dateOptions)
        assertEquals(100000L, r.totalCents)
        assertEquals(listOf(100000L, 90000L), r.totalOptions)   // cash tendered is never offered
    }

    @Test
    fun governmentReceiptPhotoA() =
        assertGovernmentReceipt(photoA, "DEPARTMENT FOR REGISTRATION OF PERSONS")

    @Test
    fun governmentReceiptPhotoB() =
        assertGovernmentReceipt(photoB, "DEPARTMENT T FOR REGISTRATION OF PERSONS")

    @Test
    fun amountsWithDotThousandSeparators() {
        assertEquals(listOf(250000L), ReceiptParser.amountsIn("Total  2.500.00"))
        assertEquals(125000L, ReceiptParser.amountsIn("Summe 1.250,00").single())
    }

    /** Real OCR output of a German restaurant bill scanned on the phone. */
    @Test
    fun germanRestaurantBill() {
        val text = """
            Pizza Hut # 127
            Lerchenauer Strasse 42
            80809 München
            Kellner: Maria E
            Tisch 26/1
            Gäste: 3
            ***ZWISCHENRECHNUNG***
            Bestellart: Im Haus
            Bereich: Restaurant
            Limo American Straw-Lemon
            Margherita Pan S
            3 Items
            Chicken Supr Cheezy Crust
            Zwischensumme
            Steuer
            27/09/2026
            Summe38.20
        """.trimIndent()

        val r = ReceiptParser.parseText(text, today, homeCurrency = "EUR")

        assertEquals("Pizza Hut # 127", r.merchant)
        assertEquals(3820L, r.totalCents)
        assertEquals(LocalDate.of(2026, 9, 27), r.date)
    }

    @Test
    fun receiptNumberBeatsReferenceNumber() {
        val r = ReceiptParser.parseText("Shop\nRef No: 5555\nReceipt No: 1234", today)
        assertEquals("1234", r.billNumber)
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
