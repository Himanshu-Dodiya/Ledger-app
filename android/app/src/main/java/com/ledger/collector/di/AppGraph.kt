package com.ledger.collector.di

import android.content.Context
import com.ledger.collector.BuildConfig
import com.ledger.collector.data.auth.AuthRepository
import com.ledger.collector.data.auth.DataStoreSessionManager
import com.ledger.collector.data.local.LedgerDatabase
import com.ledger.collector.data.prefs.SettingsStore
import com.ledger.collector.data.remote.BackendClient
import com.ledger.collector.data.remote.HttpSyncApi
import com.ledger.collector.data.remote.SyncApi
import com.ledger.collector.data.repository.DeviceRepository
import com.ledger.collector.data.repository.GmailRepository
import com.ledger.collector.data.repository.SmsRepository
import com.ledger.collector.data.repository.SyncRepository
import com.ledger.collector.data.repository.TransactionRepository
import com.ledger.collector.data.sms.SmsReader
import com.ledger.collector.domain.filter.SenderMatcher
import com.ledger.collector.domain.filter.TransactionClassifier
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient

/**
 * Manual dependency graph held by [com.ledger.collector.LedgerApp]. Everything is
 * constructor-injected and lazy. Deliberately not Hilt: keeps the build simple and the
 * pieces trivially unit-testable.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    private val database: LedgerDatabase by lazy { LedgerDatabase.build(appContext) }
    private val smsDao by lazy { database.smsMessageDao() }
    private val transactionDao by lazy { database.transactionDao() }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    private val smsReader by lazy { SmsReader(appContext) }
    private val classifier by lazy { TransactionClassifier(SenderMatcher()) }

    // Supabase: Auth only — session persistence + auto-refresh of JWT access token.
    // PostgREST reads have been replaced by the Go ledger-api service.
    val supabase: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth) {
                sessionManager = DataStoreSessionManager(appContext)
                // Lets supabase.handleDeeplinks() import the recovery session from the
                // password-reset email link (io.ledger.collector://reset-callback#access_token=...).
                scheme = "io.ledger.collector"
                host = "reset-callback"
            }
        }
    }

    val authRepository: AuthRepository by lazy { AuthRepository(supabase) }

    // Authenticated HTTP client targeting the Go ledger-api service.
    // API_BASE_URL in local.properties → deployed Go service URL.
    // Exposed (not private) so LedgerFirebaseMessagingService can re-register the FCM token.
    val backendClient: BackendClient by lazy {
        BackendClient(BuildConfig.API_BASE_URL) { authRepository.validAccessToken() }
    }

    private val syncApi: SyncApi by lazy { HttpSyncApi(backendClient) }

    val smsRepository: SmsRepository by lazy {
        SmsRepository(smsDao, smsReader, classifier, settingsStore)
    }
    val syncRepository: SyncRepository by lazy {
        SyncRepository(smsDao, syncApi)
    }
    val transactionRepository: TransactionRepository by lazy {
        TransactionRepository(transactionDao, backendClient)
    }
    val gmailRepository: GmailRepository by lazy {
        GmailRepository(backendClient)
    }
    val deviceRepository: DeviceRepository by lazy {
        DeviceRepository(backendClient)
    }
}
