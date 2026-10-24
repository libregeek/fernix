// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase

@Composable
fun SettingsScreen(
    db: FernixDatabase,
    settings: Settings,
    scanning: Boolean,
    onRescan: () -> Unit,
    onOpenSplitRules: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(db, settings) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Int?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)
        }

        Section(stringResource(R.string.settings_library)) {
            Text(stringResource(R.string.settings_rescan_detail), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRescan, enabled = !scanning) { Text(stringResource(R.string.rescan)) }
        }

        Section(stringResource(R.string.settings_np_tags)) {
            Text(stringResource(R.string.settings_np_tags_detail), style = MaterialTheme.typography.bodyMedium)
            state.slots.forEachIndexed { index, key ->
                ListItem(
                    modifier = Modifier.clickable { picking = index },
                    headlineContent = { Text(stringResource(R.string.settings_slot, index + 1)) },
                    supportingContent = { Text(key.ifEmpty { stringResource(R.string.settings_slot_empty) }) },
                )
            }
            OutlinedButton(onClick = vm::restoreDefaults) { Text(stringResource(R.string.settings_restore_defaults)) }
        }

        Section(stringResource(R.string.settings_split_rules)) {
            Text(stringResource(R.string.settings_split_rules_detail), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenSplitRules) { Text(stringResource(R.string.settings_split_rules)) }
        }
    }

    picking?.let { slot ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(R.string.settings_pick_tag)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        ListItem(
                            modifier = Modifier.clickable { vm.setSlot(slot, ""); picking = null },
                            headlineContent = { Text(stringResource(R.string.settings_clear_slot)) },
                        )
                    }
                    items(state.keyChoices, key = { it }) { key ->
                        ListItem(
                            modifier = Modifier.clickable { vm.setSlot(slot, key); picking = null },
                            headlineContent = { Text(key) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
