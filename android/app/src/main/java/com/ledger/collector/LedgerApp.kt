package com.ledger.collector

import android.app.Application
import android.app.NotificationManager
import com.ledger.collector.di.AppGraph
import com.ledger.collector.services.LedgerFirebaseMessagingService
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class LedgerApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Load PdfBox's font/resource assets once so statement import can extract text.
        PDFBoxResourceLoader.init(applicationContext)
        // Create the notification channel once at app startup (idempotent on repeat calls).
        LedgerFirebaseMessagingService.ensureChannel(
            getSystemService(NotificationManager::class.java)
        )
    }
}
