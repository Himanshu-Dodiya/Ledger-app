package com.ledger.collector.domain.imports.pdf

/**
 * Placeholders so the registry + Import Center already account for these providers. They
 * detect their format but emit nothing yet; filling in [parse] is all a future phase needs.
 */

object PhonePeParser : StatementParser {
    override val source = "phonepe_pdf"
    override val displayName = "PhonePe"
    override fun canParse(text: String): Boolean =
        text.contains("PhonePe", ignoreCase = true) &&
            text.contains("Transaction Statement", ignoreCase = true)
    override fun parse(text: String): List<ParsedRow> = emptyList()
}

object BankStatementParser : StatementParser {
    override val source = "bank_pdf"
    override val displayName = "Bank statement"
    // Generic bank statements vary too much to fingerprint reliably yet; never auto-claims.
    override fun canParse(text: String): Boolean = false
    override fun parse(text: String): List<ParsedRow> = emptyList()
}
