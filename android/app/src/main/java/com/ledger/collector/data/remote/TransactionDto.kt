package com.ledger.collector.data.remote

import com.ledger.collector.data.local.TransactionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Shape returned by Supabase PostgREST for `public.transactions`. */
@Serializable
data class TransactionDto(
    val id: String,
    val amount: Double,
    val currency: String = "INR",
    val direction: String = "debit",
    @SerialName("merchant_raw") val merchantRaw: String? = null,
    @SerialName("merchant_normalized") val merchantNormalized: String? = null,
    val category: String = "Uncategorized",
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("txn_date") val txnDate: String,
    @SerialName("reference_id") val referenceId: String? = null,
    val source: String = "gmail",
    val reviewed: Boolean = true,
    @SerialName("is_split") val isSplit: Boolean = false,
    @SerialName("created_at") val createdAt: String,
) {
    fun toEntity() = TransactionEntity(
        id = id,
        amount = amount,
        currency = currency,
        direction = direction,
        merchantRaw = merchantRaw,
        merchantNormalized = merchantNormalized,
        category = category,
        paymentMethod = paymentMethod,
        txnDate = txnDate,
        referenceId = referenceId,
        source = source,
        reviewed = reviewed,
        isSplit = isSplit,
        createdAt = createdAt,
    )
}
