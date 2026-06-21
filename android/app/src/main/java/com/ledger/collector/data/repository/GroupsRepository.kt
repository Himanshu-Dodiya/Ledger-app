package com.ledger.collector.data.repository

import com.ledger.collector.data.remote.BackendClient
import com.ledger.collector.data.remote.GroupDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Groups (trips, flats, …): member containers for scoping shared expenses. Stateless client. */
class GroupsRepository(private val backend: BackendClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(): Result<List<GroupDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.get("/v1/groups")
            if (!resp.isSuccess) throw IOException("groups failed (${resp.code})")
            val arr = JSONObject(resp.body).optJSONArray("data") ?: JSONArray()
            json.decodeFromString(ListSerializer(GroupDto.serializer()), arr.toString())
        }
    }

    suspend fun save(id: String?, name: String, type: String?, memberIds: List<String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("name", name)
                    .put("type", type ?: JSONObject.NULL)
                    .put("member_ids", JSONArray(memberIds))
                    .toString()
                val resp = if (id == null) backend.post("/v1/groups", body)
                else backend.patch("/v1/groups/$id", body)
                if (!resp.isSuccess) throw IOException("save group failed (${resp.code})")
            }
        }

    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = backend.delete("/v1/groups/$id")
            if (!resp.isSuccess) throw IOException("delete group failed (${resp.code})")
        }
    }
}
