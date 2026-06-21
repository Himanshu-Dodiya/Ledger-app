package com.ledger.collector.domain.imports.pdf

/**
 * Source-agnostic intermediate produced by every [StatementParser]. One row == one
 * transaction extracted from a statement. This maps 1:1 onto the backend's
 * `POST /v1/ingest/batch` row shape, which in turn feeds the unified `transactions`
 * model — so a Google Pay row, a Paytm row and a bank row all converge here.
 */
data class ParsedRow(
    val amount: Double,
    val direction: Direction,
    val merchant: String?,          // payee (debit) or payer (credit), human-readable
    val counterpartyUpi: String?,   // VPA of the other party, when the statement shows it
    val bankAccount: String?,       // e.g. "State Bank of India 9347", "RBL 9246"
    val paymentMethod: String?,     // "UPI", "Credit Card", ...
    val referenceId: String?,       // UPI Txn ID / UPI Ref No — the cross-source dedupe key
    val txnDate: String,            // YYYY-MM-DD (required)
    val txnTimeIso: String?,        // full RFC3339 timestamp when the statement has a time
    val note: String?,              // remarks / tags from the statement
) {
    enum class Direction(val wire: String) { DEBIT("debit"), CREDIT("credit") }
}

/** Outcome of running a file through the importer, surfaced to the Import Center UI. */
data class ImportResult(
    val source: String,
    val fileName: String,
    val parsed: Int,
    val inserted: Int,
    val duplicates: Int,
    val errors: Int,
)
