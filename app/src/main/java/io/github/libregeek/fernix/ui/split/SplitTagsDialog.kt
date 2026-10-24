// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.split

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.FernixApp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase

/**
 * **Split tags** (spec/architecture.md §5, §9), opened from a track list's overflow menu. [trackIds]
 * gives every track of that list; the settings come from the app container.
 */
@Composable
fun SplitTagsDialog(db: FernixDatabase, trackIds: suspend () -> List<Long>, onDismiss: () -> Unit) {
    val settings = (LocalContext.current.applicationContext as FernixApp).container.settings
    val vm: SplitTagsViewModel = viewModel(
        factory = viewModelFactory { initializer { SplitTagsViewModel(db, settings, trackIds) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val stage = state.stage

    AlertDialog(
        // Not dismissible by tapping outside while rules are being applied.
        onDismissRequest = { if (stage !is SplitStage.Applying) onDismiss() },
        title = { Text(stringResource(R.string.split_title)) },
        text = {
            when (stage) {
                SplitStage.Choose -> Choose(state, vm)
                SplitStage.Searching -> Text(stringResource(R.string.split_searching))
                is SplitStage.Preview -> Preview(stage, vm)
                is SplitStage.Applying -> Column {
                    Text(stringResource(R.string.split_applying, stage.done, stage.total))
                    LinearProgressIndicator(
                        progress = { if (stage.total == 0) 0f else stage.done / stage.total.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                is SplitStage.Done ->
                    Text(pluralStringResource(R.plurals.split_done, stage.rules, stage.rules))
            }
        },
        confirmButton = {
            when (stage) {
                SplitStage.Choose -> TextButton(onClick = vm::search, enabled = state.canSearch) {
                    Text(stringResource(R.string.split_preview))
                }
                is SplitStage.Preview -> TextButton(
                    onClick = vm::apply,
                    enabled = stage.rows.any { it.checked },
                ) { Text(stringResource(R.string.split_apply, stage.rows.count { it.checked })) }
                is SplitStage.Done -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.split_close)) }
                else -> {}
            }
        },
        dismissButton = {
            when (stage) {
                SplitStage.Choose, SplitStage.Searching ->
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                is SplitStage.Preview -> TextButton(onClick = vm::back) { Text(stringResource(R.string.split_back)) }
                else -> {}
            }
        },
    )
}

@Composable
private fun Choose(state: SplitState, vm: SplitTagsViewModel) {
    Column {
        Text(stringResource(R.string.split_separators), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SplitState.STANDARD.forEachIndexed { i, separator ->
                Row(
                    Modifier.clickable { vm.toggleStandard(i) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = state.standard[i], onCheckedChange = { vm.toggleStandard(i) })
                    Text("\u201C$separator\u201D")
                }
            }
        }
        OutlinedTextField(
            value = state.custom,
            onValueChange = vm::setCustom,
            label = { Text(stringResource(R.string.split_custom)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.split_tags),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            itemsIndexed(state.keys, key = { _, k -> k.keyId }) { _, key ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleKey(key.keyId) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = key.checked, onCheckedChange = { vm.toggleKey(key.keyId) })
                    Text(key.key)
                }
            }
        }
    }
}

@Composable
private fun Preview(stage: SplitStage.Preview, vm: SplitTagsViewModel) {
    if (stage.rows.isEmpty()) {
        Text(stringResource(R.string.split_nothing))
        return
    }
    Column {
        Row {
            TextButton(onClick = { vm.setAll(true) }) { Text(stringResource(R.string.split_all)) }
            TextButton(onClick = { vm.setAll(false) }) { Text(stringResource(R.string.split_none)) }
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            itemsIndexed(stage.rows, key = { _, r -> "${r.candidate.keyId}:${r.candidate.valueId}" }) { index, row ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleRow(index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = row.checked, onCheckedChange = { vm.toggleRow(index) })
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${row.candidate.key}: ${row.candidate.value}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "\u2192 " + row.candidate.parts.joinToString(" | "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.split_tracks_in_library,
                                row.candidate.libraryTracks,
                                row.candidate.libraryTracks,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
