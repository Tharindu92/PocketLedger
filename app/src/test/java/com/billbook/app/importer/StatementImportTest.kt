package com.billbook.app.importer

import com.billbook.app.data.Transaction
import com.billbook.app.data.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatementImportTest {

    private val oct28 = LocalDate.of(2026, 10, 28)

    @Test
    fun n26CsvCurrentFormat() {
        val csv = """
            "Booking Date","Value Date","Partner Name","Partner Iban","Type","Payment Reference","Account Name","Amount (EUR)","Original Amount","Original Currency","Exchange Rate"
            "2026-10-28","2026-10-28","DEPARTMENT FOR REGISTRATION","","Presentment","","Main Account","-7.42","-2500.00","LKR","336.93"
            "2026-10-30","2026-10-30","Jane Doe","DE89370400440532013000","Credit Transfer","Rent share, October","Main Account","400.00","","",""
            "2026-10-31","2026-10-31","REWE Markt","","Presentment","","Main Account","-23.10","-23.10","EUR","1"
        """.trimIndent()

        val r = BankCsvParser.parse(csv, fallbackCurrency = "LKR")

        assertEquals("N26 CSV", r.source)
        assertEquals("EUR", r.currency)
        assertEquals(3, r.lines.size)
        val card = r.lines[0]
        assertEquals(oct28, card.date)
        assertEquals(-742L, card.amountCents)
        assertEquals("EUR", card.currency)
        assertEquals("DEPARTMENT FOR REGISTRATION", card.party)
        assertEquals(250000L, card.originalAmountCents)
        assertEquals("LKR", card.originalCurrency)
        val income = r.lines[1]
        assertEquals(40000L, income.amountCents)
        assertEquals("Rent share, October", income.reference)
        assertEquals("Credit Transfer", income.kind)
        assertNull(r.lines[2].originalCurrency)      // same currency is not "foreign"
    }

    @Test
    fun n26CsvOlderFormat() {
        val csv = "\"Date\",\"Payee\",\"Account number\",\"Transaction type\",\"Payment reference\",\"Amount (EUR)\",\"Amount (Foreign Currency)\",\"Type Foreign Currency\",\"Exchange Rate\"\n" +
            "\"2026-10-28\",\"Pizza Hut\",\"\",\"MasterCard Payment\",\"\",\"-38.2\",\"\",\"\",\"\"\n"
        val line = BankCsvParser.parse(csv, "EUR").lines.single()
        assertEquals(-3820L, line.amountCents)
        assertEquals("Pizza Hut", line.party)
        assertEquals("MasterCard Payment", line.kind)
    }

    @Test
    fun germanSemicolonCsvWithPreambleAndShortDates() {
        val csv = """
            "Kontonummer:";"DE12 3456"
            "Zeitraum:";"01.10.2026 - 31.10.2026"

            "Auftragskonto";"Buchungstag";"Valutadatum";"Buchungstext";"Verwendungszweck";"Beguenstigter/Zahlungspflichtiger";"Betrag";"Waehrung"
            "DE12";"28.10.26";"28.10.26";"KARTENZAHLUNG";"Einkauf";"Lidl sagt Danke";"-1.234,56";"EUR"
            "DE12";"29.10.26";"29.10.26";"GUTSCHRIFT";"Gehalt";"ACME GmbH";"2.500,00";"EUR"
        """.trimIndent()

        val r = BankCsvParser.parse(csv, "LKR")

        assertEquals(2, r.lines.size)
        assertEquals(oct28, r.lines[0].date)
        assertEquals(-123456L, r.lines[0].amountCents)
        assertEquals("Lidl sagt Danke", r.lines[0].party)
        assertEquals("EUR", r.lines[0].currency)
        assertEquals(250000L, r.lines[1].amountCents)
    }

    @Test
    fun debitAndCreditColumnsAndUsDates() {
        val csv = "Date,Description,Debit,Credit\n10/28/2026,Coffee,4.50,\n10/30/2026,Refund,,12.00\n"
        val r = BankCsvParser.parse(csv, "USD")
        assertEquals(listOf(-450L, 1200L), r.lines.map { it.amountCents })
        assertEquals(oct28, r.lines[0].date)             // 10/28 can only be month-first
        assertEquals("USD", r.lines[0].currency)
    }

    @Test
    fun unrecognisedCsvAsksForColumnsAndThenReadsThem() {
        val csv = "When;What;How much\n28.10.2026;Bakery;-3,20\n"
        val first = BankCsvParser.parse(csv, "EUR")
        assertEquals(listOf("When", "What", "How much"), first.unrecognisedHeader)

        val second = BankCsvParser.parse(csv, "EUR", BankCsvParser.Columns(date = 0, amount = 2, party = 1))
        assertEquals(-320L, second.lines.single().amountCents)
        assertEquals("Bakery", second.lines.single().party)
    }

    @Test
    fun amountsAndDatesAsBanksPrintThem() {
        assertEquals(-123456L, StatementValues.parseAmount("-1.234,56 €"))
        assertEquals(-123456L, StatementValues.parseAmount("1,234.56-"))
        assertEquals(-742L, StatementValues.parseAmount("(7.42)"))
        assertEquals(250000L, StatementValues.parseAmount("EUR 2.500,00"))
        assertEquals(500L, StatementValues.parseAmount("5"))
        assertNull(StatementValues.parseAmount(""))
        assertEquals(oct28, StatementValues.parseDate("28.10.2026"))
        assertEquals(oct28, StatementValues.parseDate("2026-10-28 09:05"))
        assertEquals(oct28, StatementValues.parseDate("28 Okt 2026"))
        assertEquals(oct28, StatementValues.parseDate("28. Oktober", defaultYear = 2026))
    }

    @Test
    fun pdfRowsWithSignsDatesAboveAndBalances() {
        val rows = listOf(
            "Kontoauszug Oktober 2026",
            "Datum Beschreibung Betrag",
            "28.10.2026 DEPARTMENT FOR REGISTRATION -7,42 €",
            "Jane Doe",
            "30.10.2026 Gutschrift +400,00 €",
            "31.10.2026",
            "REWE Markt 23,10 €",
            "Neuer Kontostand 1.234,56 €",
        )
        val r = BankPdfParser.parse(rows, "LKR", year = 2026)

        assertEquals("EUR", r.currency)
        assertEquals(3, r.lines.size)
        assertEquals(-742L, r.lines[0].amountCents)
        assertEquals("DEPARTMENT FOR REGISTRATION", r.lines[0].party)
        assertFalse(r.lines[0].uncertain)
        assertEquals(40000L, r.lines[1].amountCents)
        // No sign printed and dated by the line above: imported as an expense, marked to check.
        assertEquals(-2310L, r.lines[2].amountCents)
        assertEquals(LocalDate.of(2026, 10, 31), r.lines[2].date)
        assertTrue(r.lines[2].uncertain)
    }

    /** Layout of an American Express (Germany) PDF statement, personal details replaced. */
    @Test
    fun amexCardStatementPdf() {
        val rows = listOf(
            "PAYBACK American Express Karte americanexpress.de",
            "Monatsabrechnung",
            "Angefertigt für Ihre Karten-Nr. Datum",
            "MAX MUSTERMANN xxxx-xxxxxx-00000 08.10.26",
            "Saldo der letzten Gutschriften Neue Belastungen Neuer Saldo Zu zahlender",
            "Abrechnung in EUR in EUR in EUR in EUR Betrag in EUR",
            "0,00 0,00 1.769,19 1.769,19 1.769,19",
            "Abrechnungszeitraum vom 09.09.26 bis 08.10.26",
            "Umsatz vom Buchungsdatum Details Betrag in Fremdwährung Betrag EUR",
            "18.09 19.09 KAUFLAND B. REINICKEND BERLIN 2,49",
            "19.09 19.09 BER-HALLEN AM BORSIGTU BERLIN 35,90",
            "PBPAY004052420b6b8644859d472",
            "27.09 28.09 JET TANKSTELLE XJ05508 BERLIN 73,45",
            "1210 Vielen Dank fuer Ihre Tankung",
            "29.09 29.09 SEILBAHN ZUGSPITZE GARMISCH-PARTEN USD 52,00 46,10",
            "30.09 30.09 ZAHLUNG ERHALTEN. VIELEN DANK -500,00",
            "02.10 02.10 KAUFLAND B. REINICKEND BERLIN -12,00",
            "05.10 05.10 TURKISH AIRLINES TURKIS UNITED KINGDOM 801,77",
            "Saldo des laufenden Monats für MAX MUSTERMANN 1.769,19",
        )
        val r = BankPdfParser.parse(rows, "LKR", today = LocalDate.of(2026, 11, 9))

        assertEquals("EUR", r.currency)
        assertEquals(listOf(-249L, -3590L, -7345L, -4610L, 1200L, -80177L), r.lines.map { it.amountCents })
        val first = r.lines.first()
        assertEquals(LocalDate.of(2026, 9, 18), first.date)          // purchase day, year from the statement
        assertEquals("KAUFLAND B. REINICKEND BERLIN", first.party)
        assertFalse(r.lines.any { it.uncertain })                   // card statements have clear signs
        val foreign = r.lines[3]
        assertEquals(5200L, foreign.originalAmountCents)
        assertEquals("USD", foreign.originalCurrency)
        assertEquals("SEILBAHN ZUGSPITZE GARMISCH-PARTEN", foreign.party)
    }

    @Test
    fun shortDatesTakeThePreviousYearAcrossNewYear() {
        val rows = listOf("Ihre Karten-Nr. xxxx Datum 08.01.27", "28.12 29.12 GIFT SHOP BERLIN 20,00")
        val line = BankPdfParser.parse(rows, "EUR", today = LocalDate.of(2027, 1, 20)).lines.single()
        assertEquals(LocalDate.of(2026, 12, 28), line.date)
    }

    @Test
    fun bankStatementPayingTheCardIsNotACardStatement() {
        val rows = listOf("Kontoauszug", "05.10.2026 American Express Europe S.A. -1.769,19 €")
        val line = BankPdfParser.parse(rows, "EUR", today = LocalDate.of(2026, 11, 9)).lines.single()
        assertEquals(-176919L, line.amountCents)
    }

    /** N26 prints the foreign amount on a line of its own under a card payment. */
    @Test
    fun n26ForeignAmountLineBelongsToTheBookingAbove() {
        val rows = listOf(
            "Beschreibung Verbuchungsdatum Betrag",
            "BAZAAR ISTANBUL 06.10.2026 -13,14€",
            "Mastercard • Shopping",
            "Wertstellung 06.10.2026",
            "Ursprungsbetrag 720.00 TRY | Wechselkurs 0.01825",
            "HOTEL COLOMBO 02.10.2026 -40,50€",
            "Ursprungsbetrag 15000.00 LKR | Wechselkurs 0.00270",
            "Ausgehende Transaktionen -53,64€",
        )
        val r = BankPdfParser.parse(rows, "EUR", today = LocalDate.of(2026, 11, 9))
        assertEquals(listOf(-1314L, -4050L), r.lines.map { it.amountCents })
        assertEquals(listOf(72000L, 1500000L), r.lines.map { it.originalAmountCents })
        assertEquals(listOf("TRY", "LKR"), r.lines.map { it.originalCurrency })
        assertFalse(r.lines.any { it.uncertain })
    }

    /** Text rows of a summary box: labels on one line, their values on the next. */
    @Test
    fun pdfSummaryBoxesAreNotBookings() {
        val rows = listOf(
            "Period: 28 Oct 2026 – 02 Nov 2026 Currency: EUR",
            "Total income Total expenses Net surplus",
            "400.00 30.52 369.48",
            "Closing balance",
            "369.48",
            "28/10/2026 REWE Markt -23.10",
        )
        val r = BankPdfParser.parse(rows, "EUR", year = 2026)
        assertEquals(listOf(-2310L), r.lines.map { it.amountCents })
    }

    @Test
    fun foreignCardPaymentBecomesBillAmountWithPaidAmount() {
        val line = StatementLine(oct28, -742, "EUR", "DEPARTMENT FOR REGISTRATION", kind = "MasterCard Payment",
            originalAmountCents = 250000, originalCurrency = "LKR")
        val t = ImportMapping.toTransaction(line, TxType.EXPENSE, profileId = 2)
        assertEquals(250000L, t.amountCents)
        assertEquals("LKR", t.currency)
        assertEquals(742L, t.paidAmountCents)
        assertEquals("EUR", t.paidCurrency)
        assertEquals(2L, t.profileId)
        assertEquals("Card", t.paymentMethod)
        assertEquals("Government & Fees", t.category)
    }

    @Test
    fun duplicatesMatchRecordedOrPaidAmountsNearTheDate() {
        val line = StatementLine(oct28, -742, "EUR", "DRP", originalAmountCents = 250000, originalCurrency = "LKR")
        fun saved(cents: Long, currency: String, day: LocalDate, paid: Long? = null, paidIn: String? = null) =
            Transaction(type = TxType.EXPENSE, amountCents = cents, currency = currency, dateEpochDay = day.toEpochDay(),
                paidAmountCents = paid, paidCurrency = paidIn)

        assertTrue(ImportMapping.isDuplicate(line, listOf(saved(742, "EUR", oct28.plusDays(1)))))          // from N26 notification
        assertTrue(ImportMapping.isDuplicate(line, listOf(saved(250000, "LKR", oct28.minusDays(2)))))      // scanned bill
        assertTrue(ImportMapping.isDuplicate(line, listOf(saved(250000, "LKR", oct28, 742, "EUR"))))
        assertFalse(ImportMapping.isDuplicate(line, listOf(saved(742, "EUR", oct28.plusDays(10)))))
        assertFalse(ImportMapping.isDuplicate(line, listOf(saved(743, "EUR", oct28))))
    }
}
