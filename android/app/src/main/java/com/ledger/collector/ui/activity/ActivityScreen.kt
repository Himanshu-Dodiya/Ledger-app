package com.ledger.collector.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledger.collector.ui.inbox.InboxScreen
import com.ledger.collector.ui.inbox.InboxViewModel
import com.ledger.collector.ui.split.SplitSheet
import com.ledger.collector.ui.split.SplitViewModel
import com.ledger.collector.ui.transactions.TransactionsScreen
import com.ledger.collector.ui.transactions.TransactionsViewModel

/**
 * "Activity" tab: hosts the to-review queue and the full history behind a segmented toggle,
 * so the bottom bar stays at five primary destinations as the app grows.
 */
@Composable
fun ActivityScreen(
    inbox: InboxViewModel,
    txns: TransactionsViewModel,
    split: SplitViewModel,
    modifier: Modifier = Modifier,
) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    val selectedTxn by split.selectedTxn.collectAsStateWithLifecycle()

    Column(modifier) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = segment == 0,
                onClick = { segment = 0 },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("To review") }
            SegmentedButton(
                selected = segment == 1,
                onClick = { segment = 1 },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("History") }
        }
        if (segment == 0) InboxScreen(vm = inbox)
        else TransactionsScreen(vm = txns, onRowClick = { split.openTxn(it) })
    }

    selectedTxn?.let { txn ->
        SplitSheet(vm = split, txn = txn, onDismiss = { split.closeTxn() })
    }
}
