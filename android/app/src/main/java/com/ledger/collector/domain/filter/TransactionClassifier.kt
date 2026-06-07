package com.ledger.collector.domain.filter

/**
 * Combines the three confidence stages into a 0..100 score and an isTransactional verdict.
 * Phase 1 does NOT extract amount/merchant/date — it only decides "likely transactional?"
 * so the (mocked, later real) backend can do authoritative parsing.
 *
 * Tuned for RECALL: amount+keyword (70) or sender+keyword (60) clears the bar; OTP/promo
 * noise is demoted so it usually falls below it.
 */
class TransactionClassifier(
    private val senderMatcher: SenderMatcher = SenderMatcher(),
    private val threshold: Int = 50,
) {
    data class Result(val isTransactional: Boolean, val score: Int)

    // Stage 2 — transaction keywords (broad, optimised for recall).
    private val keywords = Regex(
        "\\b(debited|credited|debit|credit|spent|withdrawn|withdrawal|purchase|" +
            "transaction|txn|payment|paid|received|transfer(red)?|deposited|" +
            "upi|imps|neft|rtgs|pos|atm|emi|autopay|mandate|refund|cashback)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Stage 3 — amount tokens. Symbol/prefix OR a verb-led bare amount (SBI UPI "debited by 500.0").
    private val amount = Regex(
        "(₹|rs\\.?|inr)\\s?[\\d,]+(\\.\\d{1,2})?" +
            "|\\b(debited|credited|spent|paid|withdrawn)\\s+(by|for|with)?\\s*[\\d,]+(\\.\\d{1,2})?",
        RegexOption.IGNORE_CASE,
    )

    // Demote obvious non-transactions.
    private val skip = Regex(
        "\\botp\\b|one[- ]?time\\s*password|verification code|do not share|" +
            "is your (otp|code)|statement is ready|e-?statement",
        RegexOption.IGNORE_CASE,
    )

    fun classify(sender: String, body: String): Result {
        val senderScore = senderMatcher.score(sender)                 // 0..30
        val keywordScore = if (keywords.containsMatchIn(body)) 30 else 0
        val amountScore = if (amount.containsMatchIn(body)) 40 else 0
        var score = senderScore + keywordScore + amountScore
        if (skip.containsMatchIn(body)) score -= 45                   // OTP/promo penalty
        score = score.coerceIn(0, 100)
        return Result(isTransactional = score >= threshold, score = score)
    }
}
