package com.billbook.app.data

object Categories {
    /** Money moved into stocks, ETFs or a savings plan: kept, not spent, so Insights counts it as saved. */
    const val INVESTMENTS = "Investments"

    val expense = listOf(
        "Groceries",
        "Drugstore & Personal Care",
        "Household Supplies",
        "Clothing",
        "Electronics",
        "Dining",
        "Fuel",
        "Transport",
        "Travel",
        "Sports & Fitness",
        "Entertainment",
        "Gifts",
        "Utilities",
        "Rent",
        "Office Supplies",
        "Software & Subscriptions",
        "Repairs & Maintenance",
        "Professional Services",
        "Marketing",
        "Medical",
        "Insurance & Taxes",
        "Education",
        "Bank Charges",
        "Government & Fees",
        "Family Support",
        INVESTMENTS,
        "Other",
    )

    val income = listOf(
        "Sales",
        "Services / Fees",
        "Salary",
        "Interest",
        "Dividends",
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
