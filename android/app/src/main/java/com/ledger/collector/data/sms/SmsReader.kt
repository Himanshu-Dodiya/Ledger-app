package com.ledger.collector.data.sms

import android.content.Context
import android.provider.Telephony

/**
 * Reads the system SMS inbox via ContentResolver. Discovery is by provider `_id`
 * (`_id > minExclusiveId`) ordered ascending — NOT by timestamp, which can silently miss
 * messages whose `date` is out of insertion order. An optional `sinceDateMillis` floor is
 * used only for the one-time historical import window.
 */
class SmsReader(private val context: Context) {

    data class Raw(
        val providerId: Long,
        val sender: String,
        val body: String,
        val receivedAt: Long,
    )

    fun read(minExclusiveId: Long, sinceDateMillis: Long?): List<Raw> {
        val cols = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        val selection = StringBuilder("${Telephony.Sms._ID} > ?")
        val args = mutableListOf(minExclusiveId.toString())
        if (sinceDateMillis != null) {
            selection.append(" AND ${Telephony.Sms.DATE} >= ?")
            args.add(sinceDateMillis.toString())
        }

        val out = ArrayList<Raw>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, // content://sms/inbox
            cols,
            selection.toString(),
            args.toTypedArray(),
            "${Telephony.Sms._ID} ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val body = c.getString(iBody) ?: continue
                out.add(
                    Raw(
                        providerId = c.getLong(iId),
                        sender = c.getString(iAddr) ?: "",
                        body = body,
                        receivedAt = c.getLong(iDate),
                    )
                )
            }
        }
        return out
    }

    /** Highest `_id` currently in the inbox (used to skip historical import: start "now"). */
    fun currentMaxProviderId(): Long {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            "${Telephony.Sms._ID} DESC LIMIT 1",
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0L
    }
}
