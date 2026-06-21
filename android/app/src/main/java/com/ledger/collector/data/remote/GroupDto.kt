package com.ledger.collector.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroupMemberDto(val id: String, val name: String)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val type: String? = null,
    val members: List<GroupMemberDto> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class SharedExpenseDto(
    @SerialName("transaction_id") val transactionId: String,
    val merchant: String? = null,
    val amount: Double,
    @SerialName("txn_date") val txnDate: String,
    @SerialName("their_share") val theirShare: Double,
    @SerialName("they_paid") val theyPaid: Boolean = false,
)

@Serializable
data class TimelineDto(
    @SerialName("person_id") val personId: String,
    val net: Double = 0.0,
    @SerialName("total_shared") val totalShared: Double = 0.0,
    @SerialName("shared_expenses") val sharedExpenses: List<SharedExpenseDto> = emptyList(),
    val settlements: List<SettlementDto> = emptyList(),
)
