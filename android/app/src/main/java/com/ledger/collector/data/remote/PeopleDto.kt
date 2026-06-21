package com.ledger.collector.data.remote

import com.ledger.collector.data.local.PersonEntity
import com.ledger.collector.data.local.TagEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val color: String = "#6366F1",
    @SerialName("created_at") val createdAt: String = "",
) {
    fun toEntity() = TagEntity(id = id, name = name, color = color, createdAt = createdAt)
}

@Serializable
data class PersonDto(
    val id: String,
    val name: String,
    val phone: String? = null,
    @SerialName("upi_id") val upiId: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val tags: List<TagDto> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
) {
    fun toEntity() = PersonEntity(
        id = id, name = name, phone = phone, upiId = upiId,
        imageUrl = imageUrl, createdAt = createdAt,
    )
}
