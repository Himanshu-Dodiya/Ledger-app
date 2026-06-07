package com.ledger.collector.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.local.SmsMessageEntity
import com.ledger.collector.data.local.TransactionEntity
import com.ledger.collector.ui.components.SourceBadge
import com.ledger.collector.ui.components.formatAmount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
private fun ts(v: Long): String = if (v <= 0) "never" else fmt.format(Date(v))

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permission by vm.permissionGranted.collectAsStateWithLifecycle()
    val importDone by vm.importDone.collectAsStateWithLifecycle()
    val interval by vm.syncInterval.collectAsStateWithLifecycle()
    val lastSync by vm.lastSyncAt.collectAsStateWithLifecycle()
    val unreviewed by vm.unreviewedCount.collectAsStateWithLifecycle()
    val recentTxns by vm.recentTransactions.collectAsStateWithLifecycle()
    val syncing by vm.isSyncing.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()

    if (permission && !importDone) {
        ImportDialog(onPick = { vm.importHistory(it) }, onSkip = { vm.skipImport() })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Headline: how many transactions await review.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("To review", style = MaterialTheme.typography.labelMedium)
                Text("$unreviewed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Open the Inbox tab to categorize them.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (!permission) {
            StatusCard(title = "SMS permission", value = "Denied") {
                Button(onClick = onRequestPermission) { Text("Grant") }
            }
        }

        StatusCard(
            title = "Background sync",
            value = if (interval.isPeriodic) "On · ${interval.label}" else "Manual only",
        )
        StatusCard(title = "Last sync", value = ts(lastSync))

        Button(
            onClick = { vm.syncNow() },
            enabled = permission && !syncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (syncing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Sync now")
            }
        }

        // Last sync result — tells you exactly what happened (uploaded / created / failed).
        syncStatus?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Recent synced transactions (both sources).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent transactions", fontWeight = FontWeight.SemiBold)
                if (recentTxns.isEmpty()) {
                    Text("Nothing synced yet. Tap \"Sync now\".", style = MaterialTheme.typography.bodyMedium)
                } else {
                    recentTxns.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider()
                        TxnRow(t)
                    }
                }
            }
        }

        DebugSection(vm)
    }
}

@Composable
private fun TxnRow(t: TransactionEntity) {
    val isCredit = t.direction.equals("credit", ignoreCase = true)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceBadge(t.source)
                Text(t.txnDate, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                t.merchantRaw?.takeIf { it.isNotBlank() } ?: "Unknown merchant",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            (if (isCredit) "+" else "") + formatAmount(t.amount),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DebugSection(vm: HomeViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val total by vm.totalCount.collectAsStateWithLifecycle()
    val txnal by vm.transactionalCount.collectAsStateWithLifecycle()
    val pending by vm.pendingSyncCount.collectAsStateWithLifecycle()
    val synced by vm.syncedCount.collectAsStateWithLifecycle()
    val failed by vm.failedSyncCount.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val lastProcessed by vm.lastProcessed.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Debug (SMS pipeline)", fontWeight = FontWeight.SemiBold)
                Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                StatRow("Total SMS", total)
                StatRow("Transactional", txnal)
                StatRow("Pending sync", pending)
                StatRow("Synced", synced)
                StatRow("Failed sync", failed)
                HorizontalDivider()
                Text("Last processed SMS", fontWeight = FontWeight.Medium)
                if (lastProcessed == null) Text("—") else SmsRow(lastProcessed!!)
                HorizontalDivider()
                Text("Recent SMS (${recent.size})", fontWeight = FontWeight.Medium)
                recent.take(10).forEach { SmsRow(it) }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, value: String, action: @Composable (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(value, fontWeight = FontWeight.Medium)
            }
            action?.invoke()
        }
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmsRow(m: SmsMessageEntity) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                m.sender.ifBlank { "(unknown)" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                (if (m.isTransactional) "txn " else "") + "· ${m.confidenceScore} · ${ts(m.receivedAt)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(m.body.take(140), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ImportDialog(onPick: (Int) -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* force a choice */ },
        title = { Text("Import historical SMS?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pull past transaction SMS into the collector. Default is last 30 days.")
                listOf(7 to "Last 7 days", 30 to "Last 30 days", 90 to "Last 90 days", 180 to "Last 180 days")
                    .forEach { (days, label) ->
                        OutlinedButton(onClick = { onPick(days) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label)
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(30) }) { Text("Default (30d)") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}
