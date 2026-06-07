package com.ledger.collector.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ledger.collector.work.SmsWorkScheduler

/**
 * Live capture: when a new SMS arrives, kick a one-shot worker that ingests everything with
 * `_id > lastProcessedSmsId`. We don't parse here — we just trigger the pipeline so the
 * message is stored quickly even if the app is closed.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        SmsWorkScheduler.runOnce(context)
    }
}
