package com.ledger.collector.domain.imports.pdf

/**
 * One implementation per statement provider. Parsing is intentionally NOT hardcoded into a
 * single function: each provider owns its own fingerprint + extraction logic, and new
 * providers (PhonePe, individual banks) are added by dropping in another [StatementParser]
 * and registering it — no changes to the import pipeline.
 */
interface StatementParser {
    /** The `source` label persisted on every row this parser emits (e.g. "gpay_pdf"). */
    val source: String

    /** Human label for the Import Center (e.g. "Google Pay"). */
    val displayName: String

    /** Cheap header/fingerprint check: does this parser recognise the extracted text? */
    fun canParse(text: String): Boolean

    /** Extract every transaction. Implementations should skip — not throw on — junk rows. */
    fun parse(text: String): List<ParsedRow>
}

/**
 * Ordered registry of known parsers. [pick] returns the first parser that recognises the
 * text, so detection is automatic — the user picks a file, not a format.
 */
object StatementParsers {
    val all: List<StatementParser> = listOf(
        GooglePayParser,
        PaytmParser,
        PhonePeParser,
        BankStatementParser,
    )

    fun pick(text: String): StatementParser? = all.firstOrNull { it.canParse(text) }
}
