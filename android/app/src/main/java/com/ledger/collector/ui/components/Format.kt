package com.ledger.collector.ui.components

import java.util.Locale

/** The fixed category set, mirroring the web app's inbox buttons. */
val CATEGORIES = listOf(
    "Food & Dining",
    "Groceries",
    "Shopping",
    "Transport",
    "Bills & Utilities",
    "Subscriptions",
    "Entertainment",
    "Health",
    "Transfers",
    "Income",
    "Uncategorized",
)

/** ₹ with thousands separators and 2 decimals, e.g. 1234.5 -> "₹1,234.50". */
fun formatAmount(amount: Double): String = "₹" + String.format(Locale.getDefault(), "%,.2f", amount)
