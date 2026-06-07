package com.ledger.collector.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ledger.collector.LedgerApp
import com.ledger.collector.work.SmsWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-arms the periodic sync after a reboot (WorkManager's own jobs survive reboot, but we
 *  re-apply the user's stored cadence to be safe). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as LedgerApp
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val interval = app.graph.settingsStore.currentSyncInterval()
                SmsWorkScheduler.apply(context, interval)
            } finally {
                pending.finish()
            }
        }
    }
}
