package com.ledger.collector.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BucketDto(val label: String, val total: Double)

@Serializable
data class MonthPointDto(val month: String, val spend: Double = 0.0, val income: Double = 0.0)

@Serializable
data class AnalyticsDto(
    @SerialName("total_spend") val totalSpend: Double = 0.0,
    @SerialName("total_income") val totalIncome: Double = 0.0,
    val savings: Double = 0.0,
    @SerialName("by_category") val byCategory: List<BucketDto> = emptyList(),
    @SerialName("by_payment_method") val byPaymentMethod: List<BucketDto> = emptyList(),
    @SerialName("by_source") val bySource: List<BucketDto> = emptyList(),
    @SerialName("top_merchants") val topMerchants: List<BucketDto> = emptyList(),
    @SerialName("monthly_trends") val monthlyTrends: List<MonthPointDto> = emptyList(),
    @SerialName("top_people") val topPeople: List<BucketDto> = emptyList(),
)
