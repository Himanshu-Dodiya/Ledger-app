package com.ledger.collector.data.repository

import android.net.Uri
import com.ledger.collector.data.remote.BackendClient
import com.ledger.collector.domain.imports.pdf.ImportResult
import com.ledger.collector.domain.imports.pdf.ParsedRow
import com.ledger.collector.domain.imports.pdf.PdfTextExtractor
import com.ledger.collector.domain.imports.pdf.StatementParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.IOException

/**
 * Drives statement import: extract text on-device → auto-detect the provider → parse →
 * push the normalized rows to the backend's batch-ingest endpoint, which reuses the same
 * categorize + dedupe + insert path as SMS/Gmail. After a successful import it refreshes the
 * Room-backed transaction cache so the new rows appear immediately.
 *
 * Adding CSV / Gmail / manual import here later keeps the Import Center backed by one repo.
 */
class ImportRepository(
    private val extractor: PdfTextExtractor,
    private val backend: BackendClient,
    private val transactions: TransactionRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Import a PDF statement. Returns [ImportResult] with parsed/inserted/duplicate counts,
     * or a failure if the file can't be read or no parser recognises it.
     */
    suspend fun importPdf(uri: Uri, fileName: String): Result<ImportResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = extractor.extract(uri)
                    ?: throw IOException("Couldn't read this PDF.")

                val parser = StatementParsers.pick(text)
                    ?: throw IOException("Unsupported statement. Couldn't detect the format.")

                val rows = parser.parse(text)
                if (rows.isEmpty()) {
                    return@runCatching ImportResult(parser.source, fileName, 0, 0, 0, 0)
                }

                val body = json.encodeToString(
                    BatchReqDto.serializer(),
                    BatchReqDto(
                        source = parser.source,
                        fileName = fileName,
                        rows = rows.map { it.toDto() },
                    ),
                )
                val resp = backend.post("/v1/ingest/batch", body)
                if (!resp.isSuccess) throw IOException("Import failed (${resp.code}).")

                val data = JSONObject(resp.body).optJSONObject("data") ?: JSONObject()
                val result = ImportResult(
                    source = parser.source,
                    fileName = fileName,
                    parsed = rows.size,
                    inserted = data.optInt("inserted"),
                    duplicates = data.optInt("duplicates"),
                    errors = data.optInt("errors"),
                )
                transactions.refresh() // pull the newly-inserted rows into the local cache
                result
            }
        }

    /**
     * Manual transaction entry from the Import Center. Posts to the same `POST /v1/transactions`
     * endpoint the create-flow uses (server-side dedupe + merchant-rule learning apply), then
     * refreshes the local cache. Returns false on a duplicate so the UI can say so.
     */
    suspend fun createManual(
        amount: Double,
        direction: ParsedRow.Direction,
        merchant: String,
        category: String,
        txnDate: String,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("amount", amount)
                .put("direction", direction.wire)
                .put("merchant_raw", merchant)
                .put("category", category)
                .put("txn_date", txnDate)
                .put("source", "manual")
                .toString()
            val resp = backend.post("/v1/transactions", body)
            when {
                resp.isSuccess -> { transactions.refresh(); true }
                resp.code == 409 -> false // duplicate
                else -> throw IOException(resp.errorMessage())
            }
        }
    }

    private fun ParsedRow.toDto() = BatchRowDto(
        amount = amount,
        direction = direction.wire,
        merchant = merchant,
        counterpartyUpi = counterpartyUpi,
        bankAccount = bankAccount,
        paymentMethod = paymentMethod,
        referenceId = referenceId,
        txnDate = txnDate,
        txnTime = txnTimeIso,
        note = note,
    )

    @Serializable
    private data class BatchReqDto(
        val source: String,
        @SerialName("file_name") val fileName: String,
        val rows: List<BatchRowDto>,
    )

    @Serializable
    private data class BatchRowDto(
        val amount: Double,
        val direction: String,
        val merchant: String? = null,
        @SerialName("counterparty_upi") val counterpartyUpi: String? = null,
        @SerialName("bank_account") val bankAccount: String? = null,
        @SerialName("payment_method") val paymentMethod: String? = null,
        @SerialName("reference_id") val referenceId: String? = null,
        @SerialName("txn_date") val txnDate: String,
        @SerialName("txn_time") val txnTime: String? = null,
        val note: String? = null,
    )
}
