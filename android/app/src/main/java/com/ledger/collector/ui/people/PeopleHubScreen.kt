package com.ledger.collector.ui.people

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
import com.ledger.collector.ui.split.SplitViewModel

/** People tab: People (with balances + timeline) and Groups behind a segmented toggle. */
@Composable
fun PeopleHubScreen(vm: PeopleViewModel, split: SplitViewModel, modifier: Modifier = Modifier) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(segment == 0, { segment = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) {
                Text("People")
            }
            SegmentedButton(segment == 1, { segment = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) {
                Text("Groups")
            }
        }
        if (segment == 0) PeopleScreen(vm = vm, split = split) else GroupsScreen(vm = vm)
    }
}
