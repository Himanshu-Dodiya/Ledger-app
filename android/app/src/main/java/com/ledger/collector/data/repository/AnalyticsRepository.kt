package com.ledger.collector.data.repository

import com.ledger.collector.data.remote.AnalyticsDto
import com.ledger.collector.data.remote.BackendClient
import com.ledger.collector.data.remote.InsightDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.IOException

/** Reads the mobile-first analytics dashboard from the Go service. Stateless. */
class AnalyticsRepository(private val backend: BackendClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analytics(from: String? = null, to: String? = null): Result<AnalyticsDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = buildString {
                    append("/v1/analytics")
                    val params = listOfNotNull(from?.let { "from=$it" }, to?.let { "to=$it" })
                    if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
                }
                val resp = backend.get(q)
                if (!resp.isSuccess) throw IOException("analytics failed (${resp.code})")
                val data = JSONObject(resp.body).optJSONObject("data")?.toString() ?: "{}"
                json.decodeFromString(AnalyticsDto.serializer(), data)
            }
        }

    suspend fun insights(): Result<List<InsightDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.get("/v1/insights")
            if (!resp.isSuccess) throw IOException("insights failed (${resp.code})")
            val arr = JSONObject(resp.body).optJSONArray("data") ?: return@runCatching emptyList()
            json.decodeFromString(ListSerializer(InsightDto.serializer()), arr.toString())
        }
    }
}
