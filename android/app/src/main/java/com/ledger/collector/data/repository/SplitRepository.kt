package com.ledger.collector.data.repository

import com.ledger.collector.data.remote.BackendClient
import com.ledger.collector.data.remote.BalancesDto
import com.ledger.collector.data.remote.SettlementDto
import com.ledger.collector.data.remote.SplitRowDto
import com.ledger.collector.data.remote.TimelineDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Splitting, settlements and derived balances. The backend resolves per-participant owed
 * amounts and aggregates balances; this repo is a thin typed client over those endpoints.
 * Stateless (no Room cache) — split/balance data is always read fresh when a sheet opens.
 */
class SplitRepository(
    private val backend: BackendClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** One participant the caller wants in the split. value is pct/exact/share-units per method. */
    data class Participant(val personId: String?, val value: Double?)

    suspend fun getSplit(txnId: String): Result<List<SplitRowDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.get("/v1/transactions/$txnId/split")
            if (!resp.isSuccess) throw IOException("load split failed (${resp.code})")
            decodeList(resp.body, SplitRowDto.serializer())
        }
    }

    suspend fun saveSplit(
        txnId: String,
        method: String,
        payerPersonId: String?,
        participants: List<Participant>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            participants.forEach { p ->
                arr.put(JSONObject()
                    .put("person_id", p.personId ?: JSONObject.NULL)
                    .put("value", p.value ?: JSONObject.NULL))
            }
            val body = JSONObject()
                .put("method", method)
                .put("payer_person_id", payerPersonId ?: JSONObject.NULL)
                .put("participants", arr)
                .toString()
            val resp = backend.put("/v1/transactions/$txnId/split", body)
            if (!resp.isSuccess) throw IOException("save split failed (${resp.code})")
        }
    }

    suspend fun clearSplit(txnId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.delete("/v1/transactions/$txnId/split")
            if (!resp.isSuccess) throw IOException("clear split failed (${resp.code})")
        }
    }

    suspend fun balances(): Result<BalancesDto> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.get("/v1/balances")
            if (!resp.isSuccess) throw IOException("balances failed (${resp.code})")
            val data = JSONObject(resp.body).optJSONObject("data")?.toString() ?: "{}"
            json.decodeFromString(BalancesDto.serializer(), data)
        }
    }

    suspend fun timeline(personId: String): Result<TimelineDto> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.get("/v1/people/$personId/timeline")
            if (!resp.isSuccess) throw IOException("timeline failed (${resp.code})")
            val data = JSONObject(resp.body).optJSONObject("data")?.toString() ?: "{}"
            json.decodeFromString(TimelineDto.serializer(), data)
        }
    }

    suspend fun settlements(): Result<List<SettlementDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.get("/v1/settlements")
            if (!resp.isSuccess) throw IOException("settlements failed (${resp.code})")
            decodeList(resp.body, SettlementDto.serializer())
        }
    }

    /**
     * Record a settlement. [fromPersonId]/[toPersonId] null means "you". To clear what a person
     * owes you, they pay you: from = person, to = null.
     */
    suspend fun settle(
        fromPersonId: String?,
        toPersonId: String?,
        amount: Double,
        upiRef: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("from_person_id", fromPersonId ?: JSONObject.NULL)
                .put("to_person_id", toPersonId ?: JSONObject.NULL)
                .put("amount", amount)
                .put("upi_ref", upiRef ?: JSONObject.NULL)
                .put("status", "completed")
                .toString()
            val resp = backend.post("/v1/settlements", body)
            if (!resp.isSuccess) throw IOException("settle failed (${resp.code})")
        }
    }

    private fun <T> decodeList(body: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return json.decodeFromString(ListSerializer(serializer), arr.toString())
    }
}
