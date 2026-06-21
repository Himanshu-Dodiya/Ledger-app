package com.ledger.collector.data.repository

import com.ledger.collector.data.local.TransactionDao
import com.ledger.collector.data.local.TransactionEntity
import com.ledger.collector.data.remote.BackendClient
import com.ledger.collector.data.remote.TransactionDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import java.io.IOException

/**
 * Source-aware transactions for the app. Reads come from the Go ledger-api service and
 * are cached in Room so the UI renders instantly and works offline. All writes (review /
 * recategorize / delete) go through the same Go service which handles merchant-rule
 * learning and server-side deduplication.
 */
class TransactionRepository(
    private val dao: TransactionDao,
    private val backend: BackendClient,
) {
    val inbox: Flow<List<TransactionEntity>> = dao.inbox()
    val reviewed: Flow<List<TransactionEntity>> = dao.reviewed()
    val unreviewedCount: Flow<Int> = dao.unreviewedCount()
    fun recent(limit: Int = 20): Flow<List<TransactionEntity>> = dao.recent(limit)

    /** Pull the latest transactions from the Go API into the local Room cache. */
    suspend fun refresh(limit: Int = 500): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.get("/v1/transactions?limit=$limit")
            if (!resp.isSuccess) throw IOException("refresh failed: ${resp.code}")
            // Response shape: {"data": [...transactions...]}
            val dataArr = JSONObject(resp.body).optJSONArray("data")
                ?: return@runCatching 0
            val json = Json { ignoreUnknownKeys = true }
            val rows: List<TransactionDto> = json.decodeFromString(
                ListSerializer(TransactionDto.serializer()),
                dataArr.toString(),
            )
            dao.upsertAll(rows.map { it.toEntity() })
            rows.size
        }
    }

    /** Assign a category and mark reviewed; also teaches the merchant rule server-side. */
    suspend fun markReviewed(id: String, category: String): Result<Unit> {
        val prev = dao.byId(id) ?: return Result.failure(IOException("unknown transaction"))
        dao.updateReview(id, reviewed = true, category = category)
        val json = JSONObject().put("reviewed", true).put("category", category).toString()
        return patchOrRevert(id, json, prev)
    }

    /** Mark reviewed without changing the category (the "Skip" action in Uncategorized). */
    suspend fun skip(id: String): Result<Unit> {
        val prev = dao.byId(id) ?: return Result.failure(IOException("unknown transaction"))
        dao.updateReview(id, reviewed = true, category = prev.category)
        val json = JSONObject().put("reviewed", true).toString()
        return patchOrRevert(id, json, prev)
    }

    suspend fun delete(id: String): Result<Unit> {
        val prev = dao.byId(id) ?: return Result.failure(IOException("unknown transaction"))
        dao.deleteById(id)
        val resp = backend.delete("/v1/transactions/$id")
        return if (resp.isSuccess) Result.success(Unit)
        else { dao.upsertAll(listOf(prev)); Result.failure(IOException("delete failed (${resp.code})")) }
    }

    /**
     * Create a transaction (manual / QR) and return its server id so callers can attach a split.
     * Returns null on a duplicate (409). Refreshes the local cache on success.
     */
    suspend fun create(
        amount: Double,
        direction: String,
        merchant: String,
        category: String,
        txnDate: String,
        source: String,
        referenceId: String? = null,
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("amount", amount)
                .put("direction", direction)
                .put("merchant_raw", merchant)
                .put("category", category)
                .put("txn_date", txnDate)
                .put("source", source)
                .apply { referenceId?.let { put("reference_id", it) } }
                .toString()
            val resp = backend.post("/v1/transactions", body)
            when {
                resp.isSuccess -> {
                    val id = JSONObject(resp.body).optJSONObject("data")?.optString("id")
                    refresh()
                    id
                }
                resp.code == 409 -> null
                else -> throw IOException(resp.errorMessage())
            }
        }
    }

    suspend fun clear() = dao.clearAll()

    private suspend fun patchOrRevert(id: String, json: String, prev: TransactionEntity): Result<Unit> {
        val resp = backend.patch("/v1/transactions/$id", json)
        return if (resp.isSuccess) {
            Result.success(Unit)
        } else {
            dao.upsertAll(listOf(prev))
            Result.failure(IOException("update failed (${resp.code})"))
        }
    }
}
