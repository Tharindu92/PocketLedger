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
        assertEquals("Groceries", expense("dm-drogerie markt"))
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
        // Only for the same type.
        assertEquals("", CategoryGuesser.guess(TxType.EXPENSE, listOf("Equities Settlement account"), history))
    }

    @Test
    fun payeeKeyIgnoresNumbersAndLegalForm() {
        assertEquals("rewe markt", CategoryGuesser.payeeKey("REWE Markt GmbH 1234"))
        assertEquals("db vertrieb", CategoryGuesser.payeeKey("DB Vertrieb GmbH"))
        assertEquals(null, CategoryGuesser.payeeKey("1234 -"))
    }
}
