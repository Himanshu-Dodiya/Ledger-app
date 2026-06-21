package com.ledger.collector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ledger.collector.LedgerApp
import com.ledger.collector.ui.analytics.AnalyticsViewModel
import com.ledger.collector.ui.auth.LoginViewModel
import com.ledger.collector.ui.home.HomeViewModel
import com.ledger.collector.ui.imports.ImportViewModel
import com.ledger.collector.ui.inbox.InboxViewModel
import com.ledger.collector.ui.people.PeopleViewModel
import com.ledger.collector.ui.qr.QrPayViewModel
import com.ledger.collector.ui.settings.SettingsViewModel
import com.ledger.collector.ui.split.SplitViewModel
import com.ledger.collector.ui.transactions.TransactionsViewModel

/** Bridges the manual [com.ledger.collector.di.AppGraph] to Compose's `viewModel()`. */
class AppViewModelFactory(private val app: LedgerApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val g = app.graph
        return when {
            modelClass.isAssignableFrom(LoginViewModel::class.java) ->
                LoginViewModel(g.authRepository)
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(g.smsRepository, g.syncRepository, g.settingsStore, g.transactionRepository, g.authRepository)
            modelClass.isAssignableFrom(InboxViewModel::class.java) ->
                InboxViewModel(g.transactionRepository)
            modelClass.isAssignableFrom(TransactionsViewModel::class.java) ->
                TransactionsViewModel(g.transactionRepository)
            modelClass.isAssignableFrom(ImportViewModel::class.java) ->
                ImportViewModel(g.importRepository)
            modelClass.isAssignableFrom(PeopleViewModel::class.java) ->
                PeopleViewModel(g.peopleRepository, g.groupsRepository, g.splitRepository)
            modelClass.isAssignableFrom(SplitViewModel::class.java) ->
                SplitViewModel(g.splitRepository, g.peopleRepository, g.groupsRepository)
            modelClass.isAssignableFrom(QrPayViewModel::class.java) ->
                QrPayViewModel(g.transactionRepository, g.splitRepository, g.peopleRepository)
            modelClass.isAssignableFrom(AnalyticsViewModel::class.java) ->
                AnalyticsViewModel(g.analyticsRepository, g.splitRepository)
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(app, g.settingsStore, g.smsRepository, g.authRepository, g.transactionRepository, g.gmailRepository, g.deviceRepository)
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        } as T
    }
}
