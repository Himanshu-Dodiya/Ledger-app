package com.ledger.collector.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.ledger.collector.BuildConfig
import com.ledger.collector.LedgerApp
import com.ledger.collector.SMS_PERMISSIONS
import com.ledger.collector.hasSmsPermission
import com.ledger.collector.services.DeviceRegistration
import com.ledger.collector.ui.auth.LoginScreen
import com.ledger.collector.ui.auth.LoginViewModel
import com.ledger.collector.ui.activity.ActivityScreen
import com.ledger.collector.ui.analytics.AnalyticsViewModel
import com.ledger.collector.ui.home.HomeHubScreen
import com.ledger.collector.ui.home.HomeViewModel
import com.ledger.collector.ui.imports.ImportScreen
import com.ledger.collector.ui.imports.ImportViewModel
import com.ledger.collector.ui.inbox.InboxViewModel
import com.ledger.collector.ui.people.PeopleHubScreen
import com.ledger.collector.ui.people.PeopleViewModel
import com.ledger.collector.ui.qr.PaymentPreviewSheet
import com.ledger.collector.ui.qr.QrPayViewModel
import com.ledger.collector.ui.qr.QrScanScreen
import com.ledger.collector.ui.split.SplitViewModel
import com.ledger.collector.ui.settings.SettingsScreen
import com.ledger.collector.ui.settings.SettingsViewModel
import com.ledger.collector.ui.theme.LedgerCollectorTheme
import com.ledger.collector.ui.transactions.TransactionsViewModel
import com.ledger.collector.work.SmsWorkScheduler
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val factory by lazy { AppViewModelFactory(application as LedgerApp) }
    private val homeViewModel by lazy { ViewModelProvider(this, factory)[HomeViewModel::class.java] }
    private val settingsViewModel by lazy { ViewModelProvider(this, factory)[SettingsViewModel::class.java] }

    private var pendingReset by mutableStateOf(false)

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            syncPermissionState()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // Gmail Authorization: request serverAuthCode + gmail.readonly scope via legacy GMS.
    // The Credential Manager API (used for sign-in) doesn't support requesting OAuth scopes.
    private val gmailAuthLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).result
                val code = account?.serverAuthCode
                if (code != null) {
                    lifecycleScope.launch { settingsViewModel.connectGmail(code) }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncPermissionState()
        handleIntent(intent)

        setContent {
            LedgerCollectorTheme {
                val graph = (application as LedgerApp).graph
                val status by graph.authRepository.sessionStatus.collectAsStateWithLifecycle()

                when (status) {
                    is SessionStatus.Authenticated -> {
                        if (pendingReset) {
                            // Password-reset deep link takes over the whole screen —
                            // no app content behind it.
                            ResetPasswordScreen(
                                onConfirm = { newPw ->
                                    lifecycleScope.launch {
                                        graph.authRepository.updatePassword(newPw)
                                        pendingReset = false
                                    }
                                },
                                onCancel = { pendingReset = false },
                            )
                        } else {
                            LaunchedEffect(Unit) {
                                if (!hasSmsPermission(this@MainActivity)) {
                                    smsPermissionLauncher.launch(SMS_PERMISSIONS)
                                }
                                // Request POST_NOTIFICATIONS on Android 13+ and register FCM token.
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                DeviceRegistration.registerCurrent(graph.backendClient)
                            }
                            MainScaffold(
                                factory = factory,
                                settingsViewModel = settingsViewModel,
                                onRequestSmsPermission = { smsPermissionLauncher.launch(SMS_PERMISSIONS) },
                                onConnectGmail = { launchGmailAuth() },
                            )
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val login: LoginViewModel = viewModel(factory = factory)
                        LoginScreen(login)
                    }
                    else -> LoadingScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "io.ledger.collector" && data.host == "reset-callback") {
            // Import the recovery session carried in the URL fragment (access_token,
            // refresh_token, type=recovery). Once imported the user is authenticated in
            // recovery mode and can set a new password via updateUser{}.
            val graph = (application as LedgerApp).graph
            graph.supabase.handleDeeplinks(intent) {
                pendingReset = true
            }
        }
    }

    private fun launchGmailAuth() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestServerAuthCode(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/gmail.readonly"))
            .build()
        val client = GoogleSignIn.getClient(this, gso)
        // Sign out first so the account chooser always shows (not auto-selected).
        client.signOut().addOnCompleteListener { gmailAuthLauncher.launch(client.signInIntent) }
    }

    override fun onResume() {
        super.onResume()
        syncPermissionState()
    }

    private fun syncPermissionState() {
        val granted = hasSmsPermission(this)
        homeViewModel.onPermissionChanged(granted)
        if (granted) {
            lifecycleScope.launch {
                val interval = (application as LedgerApp).graph.settingsStore.currentSyncInterval()
                SmsWorkScheduler.apply(this@MainActivity, interval)
            }
        }
    }
}

@Composable
private fun MainScaffold(
    factory: AppViewModelFactory,
    settingsViewModel: SettingsViewModel,
    onRequestSmsPermission: () -> Unit,
    onConnectGmail: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val inbox: InboxViewModel = viewModel(factory = factory)
    val txns: TransactionsViewModel = viewModel(factory = factory)
    val home: HomeViewModel = viewModel(factory = factory)
    val imports: ImportViewModel = viewModel(factory = factory)
    val peopleVm: PeopleViewModel = viewModel(factory = factory)
    val splitVm: SplitViewModel = viewModel(factory = factory)
    val qrVm: QrPayViewModel = viewModel(factory = factory)
    val analyticsVm: AnalyticsViewModel = viewModel(factory = factory)
    var showScanner by rememberSaveable { mutableStateOf(false) }
    val scanned by qrVm.scanned.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Overview") },
                    label = { Text("Overview", maxLines = 1, softWrap = false) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.ReceiptLong, contentDescription = "Activity") },
                    label = { Text("Activity", maxLines = 1, softWrap = false) },
                )
                // Dedicated QR-pay entry point: opens the scanner overlay directly from the nav
                // bar (a primary action) without taking over a content destination.
                NavigationBarItem(
                    selected = false,
                    onClick = { qrVm.reset(); showScanner = true },
                    icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan & pay") },
                    label = { Text("Scan", maxLines = 1, softWrap = false) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Download, contentDescription = "Import") },
                    label = { Text("Import", maxLines = 1, softWrap = false) },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Group, contentDescription = "People") },
                    label = { Text("People", maxLines = 1, softWrap = false) },
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings", maxLines = 1, softWrap = false) },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> HomeHubScreen(
                home = home,
                analytics = analyticsVm,
                onRequestPermission = onRequestSmsPermission,
                modifier = Modifier.padding(padding),
            )
            1 -> ActivityScreen(inbox = inbox, txns = txns, split = splitVm, modifier = Modifier.padding(padding))
            2 -> ImportScreen(
                vm = imports,
                onSyncSms = { home.syncNow() },
                onSyncGmail = onConnectGmail,
                onScanQr = { showScanner = true },
                modifier = Modifier.padding(padding),
            )
            3 -> PeopleHubScreen(vm = peopleVm, split = splitVm, modifier = Modifier.padding(padding))
            else -> SettingsScreen(
                vm = settingsViewModel,
                modifier = Modifier.padding(padding),
                onConnectGmail = onConnectGmail,
            )
        }
    }

    // QR scan + pay overlay sits above the whole app while active.
    if (showScanner) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
            QrScanScreen(
                onDetected = { qrVm.onScanned(it) },
                onClose = { qrVm.reset(); showScanner = false },
            )
        }
        scanned?.let { upi ->
            PaymentPreviewSheet(
                vm = qrVm,
                upi = upi,
                onClose = { qrVm.reset(); showScanner = false },
            )
        }
    }
}

@Composable
private fun ResetPasswordScreen(onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    val mismatch = confirmPw.isNotEmpty() && newPw != confirmPw
    val canSubmit = newPw.length >= 6 && newPw == confirmPw

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Set a new password", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter a new password for your account.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = newPw,
            onValueChange = { newPw = it },
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPw,
            onValueChange = { confirmPw = it },
            label = { Text("Confirm password") },
            singleLine = true,
            isError = mismatch,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (mismatch) {
            Spacer(Modifier.height(6.dp))
            Text("Passwords don't match", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { if (canSubmit) onConfirm(newPw) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update password")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
