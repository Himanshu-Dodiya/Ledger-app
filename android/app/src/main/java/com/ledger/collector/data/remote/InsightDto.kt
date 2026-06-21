package com.ledger.collector.data.remote

import kotlinx.serialization.Serializable

/** A surfaced spending observation. AI-backed providers will reuse this exact shape. */
@Serializable
data class InsightDto(
    val type: String,
    val title: String,
    val body: String,
    val severity: String = "info", // info | warn
    val amount: Double? = null,
    val source: String = "rules",
)
