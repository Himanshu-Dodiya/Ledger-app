package com.ledger.collector.domain.imports.pdf

/**
 * Parser for the "Google Pay – Transaction statement" PDF.
 *
 * Each transaction is a block anchored by a `DD Mon, YYYY` date. We segment the extracted
 * text on those date anchors, then pull each field out of the segment independently — so we
 * don't depend on the exact left-to-right order PdfBox emits (the amount, for instance, sits
 * on the same visual row as the "Paid to …" line and can land mid-segment).
 *
 * Recognised lines inside a block:
 *   Paid to / Received from / Self transfer to  <name>     → merchant + direction
 *   UPI Transaction ID: 6060…  |  Transaction ID: 3ff9…    → reference id (dedupe key)
 *   Paid by State Bank of India 9347 | Gpay | Mastercard…  → bank account + payment method
 *   ₹34  |  ₹300.85  |  ₹2,10,040.31                       → amount
 */
object GooglePayParser : StatementParser {
    override val source = "gpay_pdf"
    override val displayName = "Google Pay"

    // "01 Mar, 2026"
    private val DATE = Regex("""(\d{1,2}) ([A-Z][a-z]{2}), (\d{4})""")
    private val TIME = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM)""")
    private val DETAIL = Regex(
        """(Paid to|Received from|Self transfer to)\s+(.+?)\s*(?=₹|UPI Transaction ID|Transaction ID|Paid by|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val REF = Regex("""(?:UPI Transaction ID|Transaction ID):\s*([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE)
    private val PAIDBY = Regex("""Paid by\s+(.+?)\s*(?=₹|UPI Transaction ID|Transaction ID|$)""", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("""₹\s?([\d,]+(?:\.\d+)?)""")

    override fun canParse(text: String): Boolean =
        text.contains("Google Pay", ignoreCase = true) &&
            text.contains("Transaction statement", ignoreCase = true)

    override fun parse(text: String): List<ParsedRow> {
        val anchors = DATE.findAll(text).toList()
        if (anchors.isEmpty()) return emptyList()

        val rows = ArrayList<ParsedRow>(anchors.size)
        for (i in anchors.indices) {
            val start = anchors[i].range.first
            val end = if (i + 1 < anchors.size) anchors[i + 1].range.first else text.length
            parseBlock(anchors[i], ParseUtil.flatten(text.substring(start, end)))?.let { rows += it }
        }
        return rows
    }

    private fun parseBlock(dateMatch: MatchResult, block: String): ParsedRow? {
        val (day, mon, year) = dateMatch.destructured
        val isoDate = ParseUtil.isoDate(day, mon, year) ?: return null

        val amountStr = AMOUNT.find(block)?.groupValues?.get(1) ?: return null
        val amount = ParseUtil.amount(amountStr) ?: return null

        val detail = DETAIL.find(block) ?: return null
        val verb = detail.groupValues[1].lowercase()
        val merchant = detail.groupValues[2].trim().ifBlank { null }
        val direction =
            if (verb.startsWith("received")) ParsedRow.Direction.CREDIT else ParsedRow.Direction.DEBIT

        val paidBy = PAIDBY.find(block)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val paymentMethod = when {
            paidBy?.contains("credit card", ignoreCase = true) == true -> "Credit Card"
            paidBy?.contains("card", ignoreCase = true) == true -> "Card"
            else -> "UPI"
        }

        val time = TIME.find(block)
        val txnTime = time?.let {
            ParseUtil.isoTime(isoDate, it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }

        return ParsedRow(
            amount = amount,
            direction = direction,
            merchant = merchant,
            counterpartyUpi = null, // GPay statements don't expose the payee VPA
            bankAccount = paidBy,
            paymentMethod = paymentMethod,
            referenceId = REF.find(block)?.groupValues?.get(1),
            txnDate = isoDate,
            txnTimeIso = txnTime,
            note = if (verb.startsWith("self transfer")) "Self transfer" else null,
        )
    }
}
