package com.billbook.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryGuesserTest {

    private fun expense(vararg text: String) = CategoryGuesser.guess(TxType.EXPENSE, text.toList())
    private fun income(vararg text: String) = CategoryGuesser.guess(TxType.INCOME, text.toList())

    @Test
    fun germanShopsAndCompanies() {
        assertEquals("Groceries", expense("REWE Markt GmbH-Zw", "MasterCard Payment"))
        assertEquals("Groceries", expense("KAUFLAND BERLIN"))
        assertEquals("Drugstore & Personal Care", expense("dm-drogerie markt"))
        assertEquals("Drugstore & Personal Care", expense("ROSSMANN 1234"))
        assertEquals("Household Supplies", expense("KiK Textilien"))
        assertEquals("Household Supplies", expense("Woolworth GmbH"))
        assertEquals("Household Supplies", expense("TEDi GmbH & Co. KG"))
        assertEquals("Clothing", expense("H&M Hennes & Mauritz"))
        assertEquals("Clothing", expense("TK Maxx"))
        assertEquals("Electronics", expense("MediaMarkt Berlin"))
        assertEquals("Education", expense("Volkshochschule Mitte", "Kursgebühr"))
        assertEquals("Education", expense("Udemy"))
        assertEquals("Insurance & Taxes", expense("HUK-COBURG Versicherung"))
        assertEquals("Insurance & Taxes", expense("Techniker Krankenkasse"))
        assertEquals("Insurance & Taxes", expense("Finanzamt Wedding", "Einkommensteuer 2025"))
        // VAT lines on an ordinary receipt don't make it a tax payment.
        assertEquals("Groceries", expense("REWE", "MwSt 19% Steuer", "TAX 1.23"))
        // "Kurs" on a foreign card payment is an exchange rate, not a course.
        assertEquals("Dining", expense("Pizzeria Roma", "Kurs 1.0850"))
        assertEquals("Medical", expense("PVS BERLIN-BRANDENBURG-HAMBURG"))
        assertEquals("Medical", expense("Apotheke am Markt"))
        assertEquals("Transport", expense("BVG Ticket"))
        assertEquals("Transport", expense("DB Vertrieb GmbH"))
        assertEquals("Fuel", expense("ARAL Station 1234"))
        assertEquals("Utilities", expense("Vodafone GmbH", "Lastschrift"))
        assertEquals("Software & Subscriptions", expense("Spotify P1234"))
        assertEquals("Dining", expense("Lieferando.de"))
        assertEquals("Dining", expense("UBER EATS"))
        assertEquals("Transport", expense("UBER *TRIP"))
        assertEquals("Rent", expense("Hausverwaltung Schmidt", "Miete November"))
        assertEquals("Government & Fees", expense("Rundfunk ARD ZDF DRADIO"))
        assertEquals("Bank Charges", expense("Kontoführungsentgelt"))
        assertEquals("Marketing", expense("Google Ads"))
        assertEquals("Investments", expense("Trade Republic Bank GmbH"))
        assertEquals("Investments", expense("Scalable Capital", "Sparplan Gebühr"))
        assertEquals("Investments", expense("Equities Settlement account"))
        assertEquals("Family Support", expense("Remitly", "Transfer to Sri Lanka"))
        assertEquals("Family Support", expense("Max Mustermann", "for Amma"))
        assertEquals("Sports & Fitness", expense("Urban Sports GmbH"))
        assertEquals("Sports & Fitness", expense("McFIT Berlin"))
        assertEquals("Sports & Fitness", expense("DECATHLON"))
        assertEquals("Entertainment", expense("CinemaxX Potsdamer Platz"))
        assertEquals("Entertainment", expense("EVENTIM", "Konzert Tickets"))
        assertEquals("Gifts", expense("Blumen Schmidt"))
        // "sport" inside "Transport" doesn't count.
        assertEquals("Transport", expense("BVG Transport"))
    }

    @Test
    fun wordsInsideOtherWordsDoNotCount() {
        // "Sollzinsen" (interest paid) is a bank charge, not "Zinsen" income; "dm" inside a word; no "bar".
        assertEquals("Bank Charges", expense("Sollzinsen"))
        assertEquals("", expense("Admiral Bar"))
        assertEquals("", expense("Max Mustermann"))
    }

    @Test
    fun income() {
        assertEquals("Salary", income("ACME GmbH", "Gehalt 10/2026"))
        assertEquals("Dividends", income("Equities Settlement account", "Dividende Apple Inc."))
        assertEquals("Interest", income("Zinsen"))
        assertEquals("Refunds", income("Amazon", "Erstattung"))
        assertEquals("Services / Fees", income("Upwork Escrow Inc."))
        // Money back from a shop or the railway is a refund.
        assertEquals("Refunds", income("DB Vertrieb GmbH"))
        assertEquals("", income("Anna Maria Beispiel"))
        // Money from a broker could be a sale or a payout, so no guess (and never "Refunds").
        assertEquals("", income("Equities Settlement account"))
    }

    @Test
    fun earlierChoiceForTheSamePayeeWins() {
        val history = CategoryGuesser.learn(
            listOf(
                PartyCategory(TxType.INCOME, "Equities Settlement account", "Dividends"),
                PartyCategory(TxType.EXPENSE, "REWE Markt GmbH", "Dining"),
                PartyCategory(TxType.EXPENSE, "REWE MARKT 0815", "Dining"),
                PartyCategory(TxType.EXPENSE, "Rewe Markt", "Groceries"),
            )
        )
        assertEquals("Dividends", CategoryGuesser.guess(TxType.INCOME, listOf("Equities Settlement account"), history))
        // Most used choice, matched despite branch numbers and legal form.
        assertEquals("Dining", CategoryGuesser.guess(TxType.EXPENSE, listOf("REWE Markt GmbH-Zw 1234"), history))
        // Only for the same type: money sent there is an investment, not "Dividends".
        assertEquals("Investments", CategoryGuesser.guess(TxType.EXPENSE, listOf("Equities Settlement account"), history))
    }

    @Test
    fun payeeKeyIgnoresNumbersAndLegalForm() {
        assertEquals("rewe markt", CategoryGuesser.payeeKey("REWE Markt GmbH 1234"))
        assertEquals("db vertrieb", CategoryGuesser.payeeKey("DB Vertrieb GmbH"))
        assertEquals(null, CategoryGuesser.payeeKey("1234 -"))
    }
}
