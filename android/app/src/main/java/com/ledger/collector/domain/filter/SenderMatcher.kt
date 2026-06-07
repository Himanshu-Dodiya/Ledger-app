package com.ledger.collector.domain.filter

/**
 * Stage 1 — sender confidence. Indian bank SMS arrive from DLT header IDs such as
 * "AD-SBIINB", "VM-HDFCBK", "JK-ICICIB", "BP-RBLBNK". We match a known-bank token inside
 * the header, and also treat the generic DLT alphabetic-header shape as a weaker signal.
 *
 * Extensible: add tokens to [bankTokens] (or pass extra ones in) — nothing is hardcoded to
 * a closed set, and the structural pattern catches banks not in the list.
 */
class SenderMatcher(extraTokens: Collection<String> = emptyList()) {

    private val bankTokens: Set<String> = (DEFAULT_TOKENS + extraTokens.map { it.uppercase() }).toSet()

    /** DLT principal-entity headers: "XY-ABCDEF" / "XY-ABCDEFG" (2-char access code + alpha id). */
    private val dltHeader = Regex("^[A-Z]{2}-[A-Z0-9]{3,}$")

    /** Returns a 0..30 sender score. */
    fun score(rawSender: String): Int {
        val sender = rawSender.uppercase().trim()
        if (sender.isEmpty()) return 0
        val compact = sender.replace(Regex("[^A-Z0-9]"), "")
        val knownBank = bankTokens.any { compact.contains(it) }
        return when {
            knownBank -> 30
            dltHeader.matches(sender) -> 18      // looks like a business header, bank unknown
            sender.any { it.isLetter() } && sender.none { it.isDigit() } -> 8 // alphabetic short code
            else -> 0                             // a 10-digit personal number, etc.
        }
    }

    fun isLikelyBank(rawSender: String): Boolean = score(rawSender) >= 18

    companion object {
        // Not exhaustive by design — extend freely.
        val DEFAULT_TOKENS = setOf(
            "SBI", "HDFC", "ICICI", "IDFC", "RBL", "BOB", "BARB", "AXIS", "KOTAK",
            "YESBNK", "YES", "FEDBNK", "FEDERAL", "CANBNK", "CNRB", "CANARA", "PNB",
            "INDUS", "IDBI", "UNION", "UBI", "BOI", "CBIN", "CENTRAL", "AU", "AUBANK",
            "DBS", "HSBC", "SCB", "CITI", "AMEX", "ONECARD", "SLICE", "PAYTM", "PHONEPE",
            "GPAY", "UPI", "AMZN", "CRED",
        )
    }
}
