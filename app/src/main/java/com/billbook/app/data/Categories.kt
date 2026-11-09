package com.billbook.app.data

object Categories {
    val expense = listOf(
        "Groceries",
        "Dining",
        "Fuel",
        "Transport",
        "Travel",
        "Utilities",
        "Rent",
        "Office Supplies",
        "Software & Subscriptions",
        "Repairs & Maintenance",
        "Professional Services",
        "Marketing",
        "Medical",
        "Bank Charges",
        "Government & Fees",
        "Other",
    )

    val income = listOf(
        "Sales",
        "Services / Fees",
        "Salary",
        "Interest",
        "Rent received",
        "Refunds",
        "Other income",
    )

    val paymentMethods = listOf(
        "Cash",
        "Card",
        "Card (Visa)",
        "Card (Mastercard)",
        "Card (Amex)",
        "Bank transfer",
        "Mobile / QR",
        "Cheque",
        "Other",
    )

    val currencies = listOf(
        "LKR", "USD", "EUR", "GBP", "INR", "AUD", "CAD", "SGD", "NZD", "AED", "SAR", "QAR",
        "JPY", "CNY", "HKD", "MYR", "THB", "PKR", "NPR", "BDT", "MVR", "CHF",
    )

    fun defaultsFor(type: TxType) = if (type == TxType.INCOME) income else expense
}
