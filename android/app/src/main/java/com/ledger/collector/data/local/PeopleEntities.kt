package com.ledger.collector.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

/** A person the user transacts with. Mirrors the backend `people` table. */
@Entity(tableName = "people")
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String?,
    val upiId: String?,
    val imageUrl: String?,
    val createdAt: String,
)

/** A first-class tag (Family, Room, Office…). Mirrors the backend `tags` table. */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val createdAt: String,
)

/** Many-to-many join: a person can carry several tags. */
@Entity(
    tableName = "people_tags",
    primaryKeys = ["personId", "tagId"],
    indices = [Index("tagId")],
)
data class PersonTagCrossRef(
    val personId: String,
    val tagId: String,
)

/** A person with their tags resolved — the shape the People UI renders. */
data class PersonWithTags(
    @Embedded val person: PersonEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PersonTagCrossRef::class,
            parentColumn = "personId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<TagEntity>,
)
