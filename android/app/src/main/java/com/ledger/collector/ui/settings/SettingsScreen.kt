package com.ledger.collector.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.data.repository.DeviceRepository
import com.ledger.collector.work.SyncInterval
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
private fun ts(v: Long): String = if (v <= 0) "never" else fmt.format(Date(v))

private fun relativeTime(iso: String): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = sdf.parse(iso) ?: return iso
        val diffMs = System.currentTimeMillis() - date.time
        when {
            diffMs < TimeUnit.MINUTES.toMillis(2)  -> "just now"
            diffMs < TimeUnit.HOURS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toMinutes(diffMs)}m ago"
            diffMs < TimeUnit.DAYS.toMillis(1)     -> "${TimeUnit.MILLISECONDS.toHours(diffMs)}h ago"
            diffMs < TimeUnit.DAYS.toMillis(30)    -> "${TimeUnit.MILLISECONDS.toDays(diffMs)}d ago"
            else                                    -> fmt.format(date)
        }
    } catch (_: Exception) { iso }
}

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    modifier: Modifier = Modifier,
    onConnectGmail: () -> Unit = {},
) {
    val interval by vm.syncInterval.collectAsStateWithLifecycle()
    val importWindow by vm.importWindowDays.collectAsStateWithLifecycle()
    val lastSync by vm.lastSyncAt.collectAsStateWithLifecycle()
    val gmailConnected by vm.gmailConnected.collectAsStateWithLifecycle()
    val gmailStatus by vm.gmailStatus.collectAsStateWithLifecycle()
    val gmailSyncing by vm.gmailSyncing.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val devicesLoading by vm.devicesLoading.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Gmail sync
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gmail sync", fontWeight = FontWeight.SemiBold)
                if (gmailConnected) {
                    Text("Connected — Go service syncs automatically every 15 min.",
                        style = MaterialTheme.typography.bodySmall)
                    gmailStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Button(
                        onClick = { vm.syncGmail() },
                        enabled = !gmailSyncing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (gmailSyncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Sync Gmail now")
                    }
                    OutlinedButton(onClick = { vm.disconnectGmail() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Disconnect Gmail")
                    }
                } else {
                    Text("Connect your Gmail to automatically sync transaction emails.",
                        style = MaterialTheme.typography.bodySmall)
                    gmailStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = onConnectGmail, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect Gmail")
                    }
                }
            }
        }

        // Registered devices
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Registered devices", fontWeight = FontWeight.SemiBold)
                    if (devicesLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { vm.loadDevices() }) { Text("Refresh") }
                    }
                }
                if (devices.isEmpty() && !devicesLoading) {
                    Text("No devices registered yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    DeviceRow(device = device, onRevoke = { vm.revokeDevice(device.id) })
                }
            }
        }

        // Account
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Account", fontWeight = FontWeight.SemiBold)
                Text(vm.email ?: "Signed in", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Log out")
                }
            }
        }

        // Sync frequency
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Sync frequency", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp))
                SyncInterval.entries.forEach { opt ->
                    IntervalRow(
                        selected = opt == interval,
                        label = opt.label,
                        onClick = { vm.setSyncInterval(opt) },
                    )
                }
            }
        }

        // Status
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Status", fontWeight = FontWeight.SemiBold)
                Text("Background sync: " +
                    if (interval.isPeriodic) "Enabled (${interval.label})" else "Disabled (manual only)",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Last sync: ${ts(lastSync)}", style = MaterialTheme.typography.bodyMedium)
                Text("Historical import window: $importWindow days",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Developer
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Developer", fontWeight = FontWeight.SemiBold)
                Text("Deletes all locally stored SMS and resets the sync cursor.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.clearLocalData() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear local data")
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceRepository.Device, onRevoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.model ?: device.platform.replaceFirstChar { it.uppercaseChar() },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Last seen ${relativeTime(device.lastSeenAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke) {
            Text("Revoke", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun IntervalRow(selected: Boolean, label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
