package com.ledger.collector.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A resolved split row returned by GET /v1/transactions/{id}/split. */
@Serializable
data class SplitRowDto(
    @SerialName("person_id") val personId: String? = null, // null = you
    @SerialName("person_name") val personName: String = "You",
    @SerialName("is_payer") val isPayer: Boolean = false,
    @SerialName("share_type") val shareType: String = "equal",
    @SerialName("share_value") val shareValue: Double? = null,
    @SerialName("share_amount") val shareAmount: Double = 0.0,
    val settled: Boolean = false,
)

/** A person's net position relative to you (positive => they owe you). */
@Serializable
data class BalanceDto(
    @SerialName("person_id") val personId: String,
    val name: String,
    val net: Double,
)

@Serializable
data class BalancesDto(
    val balances: List<BalanceDto> = emptyList(),
    @SerialName("they_owe_me") val theyOweMe: Double = 0.0,
    @SerialName("i_owe") val iOwe: Double = 0.0,
)

@Serializable
data class SettlementDto(
    val id: String,
    @SerialName("from_person_id") val fromPersonId: String? = null,
    @SerialName("from_name") val fromName: String = "You",
    @SerialName("to_person_id") val toPersonId: String? = null,
    @SerialName("to_name") val toName: String = "You",
    val amount: Double,
    val status: String = "completed",
    @SerialName("settled_at") val settledAt: String = "",
)
