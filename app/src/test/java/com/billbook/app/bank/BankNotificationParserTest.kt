package com.billbook.app.bank

import com.billbook.app.data.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankNotificationParserTest {

    private fun parse(title: String, text: String, home: String = "EUR", expenses: Boolean = false) =
        BankNotificationParser.parse(title, text, home, expenses)

    @Test
    fun n26English() {
        val a = parse("N26", "You received €50.00 from Max Mustermann")!!
        assertEquals(BankAlert(TxType.INCOME, 5000, "EUR", "Max Mustermann"), a)
    }

    @Test
    fun n26RealNotification() {
        val a = parse("N26", "You received €2 from Patabendi Maddumage Tharindu de Silva.", home = "LKR", expenses = true)
        assertEquals(BankAlert(TxType.INCOME, 200, "EUR", "Patabendi Maddumage Tharindu de Silva"), a)
    }

    @Test
    fun n26OutgoingTransferIsNotIncome() {
        val text = "Trading 212 EU GmbH has received your bank transfer of €2"
        assertNull(parse("N26", text, expenses = false))
        assertEquals(BankAlert(TxType.EXPENSE, 200, "EUR", "Trading 212 EU GmbH"), parse("N26", text, expenses = true))
    }

    /** Android's regex engine (ICU) rejects some flags the desktop JVM accepts, e.g. (?U). */
    @Test
    fun patternsUseOnlyAndroidSafeFlags() {
        val source = java.io.File("src/main/java/com/billbook/app/bank/BankNotificationParser.kt").readText()
        assert(!source.contains("(?iU)") && !source.contains("(?U)")) { "Unsupported regex flag on Android" }
    }

    @Test
    fun n26German() {
        val a = parse("Geld erhalten", "Du hast 1.250,00 € von Erika Musterfrau erhalten.")!!
        assertEquals(BankAlert(TxType.INCOME, 125000, "EUR", "Erika Musterfrau"), a)
    }

    @Test
    fun senderInTitle() {
        val a = parse("Max Mustermann", "sent you €20.00")!!
        assertEquals(BankAlert(TxType.INCOME, 2000, "EUR", "Max Mustermann"), a)
    }

    @Test
    fun sriLankanBankSms() {
        val a = parse("COMBANK", "Rs.15,000.00 credited to your account 1234 via CEFT from K PERERA", home = "LKR")!!
        assertEquals(BankAlert(TxType.INCOME, 1500000, "LKR", "K PERERA"), a)

        val b = parse("Commercial Bank", "Your A/C ****1234 has been credited with LKR 25,000.00 on 08/11/2026.", home = "LKR")!!
        assertEquals(BankAlert(TxType.INCOME, 2500000, "LKR", null), b)
    }

    @Test
    fun balanceIsNotTheAmount() {
        val a = parse("Bank", "Available balance €1,000.00. You received €50.00 from Anna")!!
        assertEquals(5000L, a.amountCents)
    }

    @Test
    fun expensesOnlyWhenEnabled() {
        assertNull(parse("Payment", "You paid €12.40 at REWE Markt"))
        val a = parse("Payment", "You paid €12.40 at REWE Markt", expenses = true)!!
        assertEquals(BankAlert(TxType.EXPENSE, 1240, "EUR", "REWE Markt"), a)
    }

    @Test
    fun ignoresCodesAndRequests() {
        assertNull(parse("N26", "Your verification code is 123456"))
        assertNull(parse("N26", "Max requested €20.00 from you"))
        assertNull(parse("N26", "Your card payment of €5.00 was declined", expenses = true))
        assertNull(parse("WhatsApp", "See you at 5"))
    }

    @Test
    fun amountFormats() {
        assertEquals(125000L, BankNotificationParser.parseAmount("1.250,00"))
        assertEquals(125000L, BankNotificationParser.parseAmount("1,250.00"))
        assertEquals(125000L, BankNotificationParser.parseAmount("1.250"))
        assertEquals(1240L, BankNotificationParser.parseAmount("12.4"))
    }
}
