package com.ledger.collector.domain.imports.pdf

/**
 * Parser for the "Paytm UPI – Passbook Payments History" PDF.
 *
 * Paytm rows are anchored by `DD Mon` immediately followed by a `H:MM AM/PM` time. Unlike
 * Google Pay, Paytm rows carry no year — it lives only in the statement period header
 * ("11 MAR'26 - 10 JUN'26"), so we parse that once and assign the year per row by month.
 *
 * Recognised inside a block:
 *   <description> | Received from <name>          → merchant + (credit when "Received from")
 *   UPI ID: someone@bank                          → counterparty VPA
 *   UPI Ref No: 2059…                             → reference id (dedupe key)
 *   Tag: # Bill Payments                          → note
 *   State Bank Of India - 47                      → bank account
 *   - Rs.20  |  + Rs.1,100                         → amount + direction (sign)
 */
object PaytmParser : StatementParser {
    override val source = "paytm_pdf"
    override val displayName = "Paytm"

    // Row anchor: date then time, possibly across a line break.
    private val ANCHOR = Regex("""(\d{1,2}) ([A-Z][a-z]{2})\s+(\d{1,2}):(\d{2})\s*(AM|PM)""")
    // Statement period: "11 MAR'26 - 10 JUN'26"
    private val PERIOD = Regex("""(\d{1,2})\s+([A-Za-z]{3})'(\d{2})\s*-\s*(\d{1,2})\s+([A-Za-z]{3})'(\d{2})""")
    private val AMOUNT = Regex("""([+-])\s*Rs\.?\s*([\d,]+(?:\.\d+)?)""")
    private val UPI_ID = Regex("""UPI ID:\s*(\S+@\S+?)(?:\s|$)""", RegexOption.IGNORE_CASE)
    private val REF = Regex("""UPI Ref No:\s*(\w+)""", RegexOption.IGNORE_CASE)
    private val ORDER = Regex("""Order ID:\s*(\w+)""", RegexOption.IGNORE_CASE)
    // Fallback tag matcher when the account couldn't be located to bound the tag span.
    private val TAG = Regex("""Tag:\s*#?\s*([A-Za-z][A-Za-z &]*?)\s*(?=UPI|Order|[+-]\s*Rs|$)""")
    // The "Your Account" value ("State Bank Of India - 47") is its own line in the statement.
    // Extracting it from the RAW (non-flattened) block — a line that is only "<bank> - NN" —
    // avoids the flattened-text ambiguity where a capitalized tag word ("Bill Payments") would
    // otherwise merge into the bank name.
    private val ACCOUNT_LINE = Regex("""(?m)^\s*([A-Z][A-Za-z& ]+ - \d{1,4})\s*$""")
    // Fallback for merged-column extraction: the account sits right before the amount.
    private val ACCOUNT_INLINE = Regex("""([A-Z][A-Za-z& ]+?)\s*-\s*(\d+)\s*(?=[+-]\s*Rs)""")
    private val LEADING_AMOUNT = Regex("""^\s*[+-]\s*Rs\.?\s*[\d,.]+\s*""")

    override fun canParse(text: String): Boolean =
        text.contains("Paytm", ignoreCase = true) &&
            text.contains("Passbook Payments History", ignoreCase = true)

    override fun parse(text: String): List<ParsedRow> {
        val period = PERIOD.find(text)
        val startYear = period?.let { 2000 + it.groupValues[3].toInt() }
        val endYear = period?.let { 2000 + it.groupValues[6].toInt() }
        val startMonth = period?.let { ParseUtil.monthNumber(it.groupValues[2])?.toInt() }

        val anchors = ANCHOR.findAll(text).toList()
        val rows = ArrayList<ParsedRow>(anchors.size)
        for (i in anchors.indices) {
            val start = anchors[i].range.first
            val end = if (i + 1 < anchors.size) anchors[i + 1].range.first else text.length
            val raw = text.substring(start, end)
            parseBlock(anchors[i], raw, ParseUtil.flatten(raw), startYear, endYear, startMonth)
                ?.let { rows += it }
        }
        return rows
    }

    private fun parseBlock(
        anchor: MatchResult,
        raw: String,
        block: String,
        startYear: Int?,
        endYear: Int?,
        startMonth: Int?,
    ): ParsedRow? {
        val day = anchor.groupValues[1]
        val mon = anchor.groupValues[2]
        val monthNum = ParseUtil.monthNumber(mon)?.toInt() ?: return null
        val year = resolveYear(monthNum, startYear, endYear, startMonth) ?: return null
        val isoDate = ParseUtil.isoDate(day, mon, year.toString()) ?: return null

        val amountMatch = AMOUNT.find(block) ?: return null
        val sign = amountMatch.groupValues[1]
        val amount = ParseUtil.amount(amountMatch.groupValues[2]) ?: return null
        val direction =
            if (sign == "+") ParsedRow.Direction.CREDIT else ParsedRow.Direction.DEBIT

        // Description sits between the time and "UPI ID:"/"UPI Ref No:".
        val descRegex = Regex("""(?:AM|PM)\s+(.+?)\s*(?=UPI ID:|UPI Ref No:|Tag:|$)""", RegexOption.IGNORE_CASE)
        var desc = descRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        desc = LEADING_AMOUNT.replace(desc, "").trim()
        val merchant = when {
            desc.startsWith("Received from", ignoreCase = true) ->
                desc.removePrefix("Received from").removePrefix("received from").trim()
            desc.isNotBlank() -> desc
            else -> null
        }

        val txnTime = ParseUtil.isoTime(
            isoDate, anchor.groupValues[3], anchor.groupValues[4], anchor.groupValues[5],
        )

        // Prefer the account on its own line; fall back to the amount-anchored match.
        val account = ACCOUNT_LINE.find(raw)?.groupValues?.get(1)?.trim()
            ?: ACCOUNT_INLINE.find(block)?.let { "${it.groupValues[1].trim()} - ${it.groupValues[2]}" }

        val tag = extractTag(block, account)
        val orderId = ORDER.find(block)?.groupValues?.get(1)
        val note = listOfNotNull(tag, orderId?.let { "Order $it" }).joinToString(" · ").ifBlank { null }

        return ParsedRow(
            amount = amount,
            direction = direction,
            merchant = merchant,
            counterpartyUpi = UPI_ID.find(block)?.groupValues?.get(1),
            bankAccount = account,
            paymentMethod = "UPI",
            referenceId = REF.find(block)?.groupValues?.get(1),
            txnDate = isoDate,
            txnTimeIso = txnTime,
            note = note,
        )
    }

    /**
     * The "Notes & Tags" value, e.g. "Bill Payments". When the account is known it bounds the
     * tag span exactly (tag sits between "#" and the account in the flattened row); otherwise
     * fall back to a delimiter-based match.
     */
    private fun extractTag(block: String, account: String?): String? {
        if (!block.contains("Tag:")) return null
        val afterHash = block.substringAfter("Tag:").substringAfter("#", "")
        if (afterHash.isBlank()) return null
        val bounded = if (account != null && afterHash.contains(account)) {
            afterHash.substringBefore(account)
        } else {
            TAG.find(block)?.groupValues?.get(1) ?: afterHash
        }
        return bounded.trim().take(40).ifBlank { null }
    }

    /** Assign the year: trivial when the statement is within one calendar year, otherwise
     *  split by month around the period's start month (handles a Dec→Jan statement). */
    private fun resolveYear(month: Int, startYear: Int?, endYear: Int?, startMonth: Int?): Int? {
        if (startYear == null) return null
        if (endYear == null || startYear == endYear || startMonth == null) return startYear
        return if (month >= startMonth) startYear else endYear
    }
}
