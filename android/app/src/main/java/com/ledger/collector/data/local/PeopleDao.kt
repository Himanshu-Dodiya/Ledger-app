package com.ledger.collector.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeopleDao {

    @Transaction
    @Query("SELECT * FROM people ORDER BY name COLLATE NOCASE")
    fun peopleWithTags(): Flow<List<PersonWithTags>>

    @Transaction
    @Query("SELECT * FROM people WHERE id = :id")
    suspend fun personWithTags(id: String): PersonWithTags?

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun tags(): Flow<List<TagEntity>>

    @Upsert
    suspend fun upsertPeople(items: List<PersonEntity>)

    @Upsert
    suspend fun upsertTags(items: List<TagEntity>)

    @Upsert
    suspend fun upsertCrossRefs(items: List<PersonTagCrossRef>)

    @Query("DELETE FROM people_tags WHERE personId = :personId")
    suspend fun clearPersonTags(personId: String)

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deletePerson(id: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: String)

    @Query("DELETE FROM people")
    suspend fun clearPeople()

    @Query("DELETE FROM tags")
    suspend fun clearTags()

    @Query("DELETE FROM people_tags")
    suspend fun clearCrossRefs()

    /** Replace the whole people graph atomically after a server refresh. */
    @Transaction
    suspend fun replaceAll(
        people: List<PersonEntity>,
        tags: List<TagEntity>,
        crossRefs: List<PersonTagCrossRef>,
    ) {
        clearCrossRefs()
        clearPeople()
        clearTags()
        upsertTags(tags)
        upsertPeople(people)
        upsertCrossRefs(crossRefs)
    }
}
