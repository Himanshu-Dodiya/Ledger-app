package com.ledger.collector.data.repository

import com.ledger.collector.data.local.PeopleDao
import com.ledger.collector.data.local.PersonTagCrossRef
import com.ledger.collector.data.local.PersonWithTags
import com.ledger.collector.data.local.TagEntity
import com.ledger.collector.data.remote.BackendClient
import com.ledger.collector.data.remote.PersonDto
import com.ledger.collector.data.remote.TagDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * People + tags, cached in Room and synced with the Go service. The backend is the source of
 * truth; reads stream from Room so the UI is instant/offline, and every mutation refreshes the
 * local graph. Underpins splitting, settlements and groups in later phases.
 */
class PeopleRepository(
    private val dao: PeopleDao,
    private val backend: BackendClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val people: Flow<List<PersonWithTags>> = dao.peopleWithTags()
    val tags: Flow<List<TagEntity>> = dao.tags()

    suspend fun person(id: String): PersonWithTags? = dao.personWithTags(id)

    /** Pull tags + people from the backend and atomically replace the local graph. */
    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tagDtos = getList("/v1/tags", TagDto.serializer())
            val personDtos = getList("/v1/people", PersonDto.serializer())

            val crossRefs = personDtos.flatMap { p ->
                p.tags.map { PersonTagCrossRef(personId = p.id, tagId = it.id) }
            }
            // People may reference tags; ensure every referenced tag exists locally.
            val allTags = (tagDtos + personDtos.flatMap { it.tags })
                .associateBy { it.id }.values.map { it.toEntity() }

            dao.replaceAll(
                people = personDtos.map { it.toEntity() },
                tags = allTags,
                crossRefs = crossRefs,
            )
        }
    }

    suspend fun createPerson(
        name: String, phone: String?, upiId: String?, tagIds: List<String>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("name", name)
                .put("phone", phone ?: JSONObject.NULL)
                .put("upi_id", upiId ?: JSONObject.NULL)
                .put("tag_ids", JSONArray(tagIds))
                .toString()
            val resp = backend.post("/v1/people", body)
            if (!resp.isSuccess) throw IOException(resp.errorMessage())

            // Optimistic local insert: decode the created person from the response and write it
            // (with its tag links) into Room immediately, so the People list updates without
            // waiting on a full network refresh. A background refresh then reconciles.
            val created = JSONObject(resp.body).optJSONObject("data")
            if (created != null) {
                val dto = json.decodeFromString(PersonDto.serializer(), created.toString())
                dao.upsertPeople(listOf(dto.toEntity()))
                dao.clearPersonTags(dto.id)
                if (dto.tags.isNotEmpty()) {
                    dao.upsertTags(dto.tags.map { it.toEntity() })
                    dao.upsertCrossRefs(dto.tags.map { PersonTagCrossRef(personId = dto.id, tagId = it.id) })
                }
            }
        }.also { refresh() } // reconcile in the background regardless of optimistic outcome
    }

    suspend fun updatePerson(
        id: String, name: String, phone: String?, upiId: String?, tagIds: List<String>,
    ): Result<Unit> = mutate {
        val body = JSONObject()
            .put("name", name)
            .put("phone", phone ?: JSONObject.NULL)
            .put("upi_id", upiId ?: JSONObject.NULL)
            .put("tag_ids", JSONArray(tagIds))
            .toString()
        backend.patch("/v1/people/$id", body)
    }

    suspend fun deletePerson(id: String): Result<Unit> = mutate { backend.delete("/v1/people/$id") }

    suspend fun createTag(name: String, color: String): Result<Unit> = mutate {
        backend.post("/v1/tags", JSONObject().put("name", name).put("color", color).toString())
    }

    /**
     * Create a tag and return the persisted entity (with its server id), upserting it locally.
     * Used so a tag created inline while editing a person can be selected immediately. The
     * backend upserts on (user, name), so calling this for an existing name returns that tag.
     */
    suspend fun createTagReturning(name: String, color: String): Result<TagEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = backend.post("/v1/tags", JSONObject().put("name", name).put("color", color).toString())
                if (!resp.isSuccess) throw IOException(resp.errorMessage())
                val data = JSONObject(resp.body).optJSONObject("data")
                    ?: throw IOException("tag create returned no data")
                val tag = json.decodeFromString(TagDto.serializer(), data.toString()).toEntity()
                dao.upsertTags(listOf(tag))
                tag
            }
        }

    suspend fun updateTag(id: String, name: String, color: String): Result<Unit> = mutate {
        backend.patch("/v1/tags/$id", JSONObject().put("name", name).put("color", color).toString())
    }

    suspend fun deleteTag(id: String): Result<Unit> = mutate { backend.delete("/v1/tags/$id") }

    // --- helpers ---

    /** Run a backend mutation then refresh the local cache so the UI reflects server state. */
    private suspend fun mutate(call: suspend () -> BackendClient.Resp): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = call()
                if (!resp.isSuccess) throw IOException(resp.errorMessage())
            }.mapCatching { refresh().getOrThrow() }
        }

    private suspend fun <T> getList(
        path: String, serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> {
        val resp = backend.get(path)
        if (!resp.isSuccess) throw IOException("GET $path failed (${resp.code})")
        val arr = JSONObject(resp.body).optJSONArray("data") ?: return emptyList()
        return json.decodeFromString(ListSerializer(serializer), arr.toString())
    }
}
