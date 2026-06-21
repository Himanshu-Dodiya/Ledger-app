package com.ledger.collector.ui.imports

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Import Center: a single place to build transaction history from every supported source.
 * PDF + manual entry are handled by [ImportViewModel]; SMS/Gmail reuse the existing flows via
 * the callbacks passed in by the host activity.
 */
@Composable
fun ImportScreen(
    vm: ImportViewModel,
    onSyncSms: () -> Unit,
    onSyncGmail: () -> Unit,
    onScanQr: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var showManual by remember { mutableStateOf(false) }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read access so the import coroutine can still open the stream.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.importPdf(uri, displayName(context, uri))
        }
    }
    fun pickPdf() = pdfPicker.launch(arrayOf("application/pdf"))

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Import", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Build your history from any source. Everything becomes one unified transaction list.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (busy || message != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        message ?: "Working…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        SectionLabel("Pay")
        ImportRow(Icons.Filled.QrCodeScanner, "Scan QR to pay", "Scan a UPI QR, split, and pay", enabled = !busy, onClick = onScanQr)

        SectionLabel("Sync")
        ImportRow(Icons.Filled.Sms, "Sync SMS", "Read transactions from bank SMS", enabled = !busy, onClick = onSyncSms)
        ImportRow(Icons.Filled.Email, "Sync Gmail", "Pull transaction emails", enabled = !busy, onClick = onSyncGmail)

        SectionLabel("Statements (PDF)")
        ImportRow(Icons.Filled.PictureAsPdf, "Import Google Pay PDF", "Google Pay transaction statement", enabled = !busy) { pickPdf() }
        ImportRow(Icons.Filled.PictureAsPdf, "Import Paytm PDF", "Paytm UPI passbook statement", enabled = !busy) { pickPdf() }
        ImportRow(Icons.Filled.AccountBalance, "Import Bank Statement", "Bank account PDF (coming soon)", enabled = !busy) { pickPdf() }

        SectionLabel("Other")
        ImportRow(Icons.Filled.TableChart, "Import CSV", "Coming soon", enabled = false) {}
        ImportRow(Icons.Filled.Add, "Manual Transaction", "Add a transaction by hand", enabled = !busy) { showManual = true }
    }

    if (showManual) {
        ManualTransactionDialog(
            onDismiss = { showManual = false },
            onSubmit = { amount, dir, merchant, category, date ->
                vm.createManual(amount, dir, merchant, category, date)
                showManual = false
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ImportRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Resolve a human file name from a content Uri (falls back to "statement.pdf"). */
private fun displayName(context: android.content.Context, uri: Uri): String {
    var name = "statement.pdf"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it }
        }
    }
    return name
}
