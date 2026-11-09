package com.billbook.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

/** Statements laid out in columns: words with positions, as read from the PDF text layer. */
class PdfColumnTest {

    private val today = LocalDate.of(2026, 11, 20)

    /** Words of one line at height [y]; each pair is (text, x where it starts); about 5 units per letter. */
    private fun line(y: Float, vararg words: Pair<String, Float>) = words.map { (text, x) ->
        PdfWord(text, page = 1, x0 = x, x1 = x + text.length * 5f, y = y, height = 10f)
    }

    private fun parse(vararg lines: List<PdfWord>) =
        BankPdfParser.parseRows(PdfLayout.rows(lines.flatMap { it }), "GBP", today = today)

    @Test
    fun wordsBecomeCellsSplitAtWideGaps() {
        val rows = PdfLayout.rows(line(100f, "TESCO" to 100f, "STORES" to 130f, "23.45" to 345f))
        assertEquals(listOf("TESCO STORES", "23.45"), rows.single().cells.map { it.text })
    }

    /** UK style: no signs at all, the column says the direction; one date heads several bookings. */
    @Test
    fun paidOutPaidInBalanceColumns() {
        val r = parse(
            line(80f, "Date" to 40f, "Description" to 100f, "Paid" to 330f, "out" to 352f, "Paid" to 410f, "in" to 432f, "Balance" to 485f),
            line(100f, "03" to 40f, "Nov" to 52f, "2026" to 70f, "TESCO" to 100f, "STORES" to 130f, "23.45" to 345f, "1,000.00" to 480f),
            line(115f, "SALARY" to 100f, "ACME" to 135f, "2,500.00" to 405f, "3,476.55" to 480f),
            line(130f, "COFFEE" to 100f, "HOUSE" to 135f, "3.10" to 350f, "3,473.45" to 480f),
            line(145f, "04" to 40f, "Nov" to 52f, "2026" to 70f, "REFUND" to 100f, "ARGOS" to 135f, "15.00" to 410f, "3,488.45" to 480f),
        )
        assertEquals(listOf(-2345L, 250000L, -310L, 1500L), r.lines.map { it.amountCents })
        assertEquals(listOf("TESCO STORES", "SALARY ACME", "COFFEE HOUSE", "REFUND ARGOS"), r.lines.map { it.party })
        // A date printed once for the day dates the bookings under it.
        assertEquals(List(3) { LocalDate.of(2026, 11, 3) } + LocalDate.of(2026, 11, 4), r.lines.map { it.date })
        assertFalse(r.lines.any { it.uncertain })
    }

    /** German layout with the balance printed before the amount. */
    @Test
    fun balanceColumnBeforeAmountIsIgnored() {
        val r = parse(
            line(80f, "Datum" to 40f, "Buchungstext" to 100f, "Saldo" to 330f, "Betrag" to 420f),
            line(100f, "28.10.2026" to 40f, "REWE" to 100f, "Markt" to 125f, "1.234,56" to 320f, "-23,10" to 420f),
            line(115f, "29.10.2026" to 40f, "Gehalt" to 100f, "3.734,56" to 320f, "2.500,00" to 415f),
        )
        assertEquals(listOf(-2310L, 250000L), r.lines.map { it.amountCents })
        assertEquals("REWE Markt", r.lines[0].party)
    }

    /** Soll / Haben columns, balance-only lines skipped. */
    @Test
    fun sollHabenColumns() {
        val r = parse(
            line(80f, "Datum" to 40f, "Vorgang" to 100f, "Soll" to 340f, "Haben" to 410f),
            line(100f, "28.10.2026" to 40f, "Miete" to 100f, "800,00" to 335f),
            line(115f, "30.10.2026" to 40f, "Erstattung" to 100f, "42,00" to 410f),
        )
        assertEquals(listOf(-80000L, 4200L), r.lines.map { it.amountCents })
        assertFalse(r.lines.any { it.uncertain })
    }

    /** Amex: "Betrag in Fremdwährung" and "Betrag EUR" columns. */
    @Test
    fun cardStatementForeignAndEuroColumns() {
        val r = parse(
            line(60f, "Ihre" to 40f, "Karten-Nr." to 62f, "Datum" to 300f),
            line(70f, "xxxx-xxxxxx-00000" to 40f, "08.10.26" to 300f),
            line(80f, "Umsatz" to 40f, "vom" to 72f, "Details" to 140f, "Betrag" to 330f, "in" to 362f, "Fremdwährung" to 374f, "Betrag" to 470f, "EUR" to 502f),
            line(100f, "18.09" to 40f, "19.09" to 70f, "KAUFLAND" to 140f, "BERLIN" to 190f, "2,49" to 500f),
            line(115f, "29.09" to 40f, "29.09" to 70f, "SEILBAHN" to 140f, "USD" to 340f, "52,00" to 362f, "46,10" to 495f),
        )
        assertEquals(listOf(-249L, -4610L), r.lines.map { it.amountCents })
        assertEquals(5200L, r.lines[1].originalAmountCents)
        assertEquals("USD", r.lines[1].originalCurrency)
        assertEquals("SEILBAHN", r.lines[1].party)
    }
}
