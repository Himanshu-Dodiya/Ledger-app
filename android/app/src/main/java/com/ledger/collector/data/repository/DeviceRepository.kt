package com.ledger.collector.data.repository

import com.ledger.collector.data.remote.BackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

class DeviceRepository(private val backend: BackendClient) {

    data class Device(
        val id: String,
        val platform: String,
        val model: String?,
        val lastSeenAt: String,   // ISO-8601
        val createdAt: String,    // ISO-8601
    )

    suspend fun list(): Result<List<Device>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.get("/v1/devices")
            if (!resp.isSuccess) throw IOException("list devices failed: ${resp.code}")
            val arr = JSONObject(resp.body).optJSONArray("data")
                ?: return@runCatching emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Device(
                    id = o.getString("id"),
                    platform = o.optString("platform", "android"),
                    model = o.optString("model").takeIf { it.isNotBlank() },
                    lastSeenAt = o.optString("last_seen_at"),
                    createdAt = o.optString("created_at"),
                )
            }
        }
    }

    suspend fun revoke(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.delete("/v1/devices/$id")
            if (!resp.isSuccess) throw IOException("revoke failed: ${resp.code}")
        }
    }
}
