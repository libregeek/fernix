// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.scan.CheckState
import io.github.libregeek.fernix.data.scan.MediaSource
import io.github.libregeek.fernix.data.scan.MediaStoreSource

/**
 * In-app folder chooser: volumes with their audio folders as a tree, a checkbox per folder
 * (ticking one includes its sub-folders). Uses no file-picker intent and no extra permission.
 */
@Composable
fun FolderChooserScreen(
    initialSelection: Set<String>,
    onDone: (Set<String>) -> Unit,
    onCancel: () -> Unit,
    media: MediaSource = MediaStoreSource(LocalContext.current),
) {
    val vm: FolderChooserViewModel = viewModel(
        factory = viewModelFactory { initializer { FolderChooserViewModel(media, initialSelection, createSavedStateHandle()) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // The ViewModel outlives the screen: read the folders again at each opening, for music added since.
    LaunchedEffect(Unit) { vm.reload() }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Text(
                stringResource(R.string.chooser_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.rows.isEmpty() -> Text(
                        stringResource(R.string.chooser_empty),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.rows, key = { it.path }) { row ->
                            FolderRowItem(row, onToggleSelected = { vm.toggleSelected(row.path) }, onToggleExpanded = { vm.toggleExpanded(row.path) })
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.chooser_cancel)) }
                Button(onClick = { onDone(state.selected) }, enabled = state.selected.isNotEmpty()) {
                    Text(stringResource(R.string.chooser_done))
                }
            }
        }
    }
}

@Composable
private fun FolderRowItem(row: FolderRow, onToggleSelected: () -> Unit, onToggleExpanded: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = row.hasChildren, onClick = onToggleExpanded)
            .padding(start = (8 + 20 * row.depth).dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (row.hasChildren) Text(if (row.expanded) "▾" else "▸", style = MaterialTheme.typography.bodyLarge)
        }
        TriStateCheckbox(
            state = when (row.state) {
                CheckState.Checked, CheckState.Covered -> ToggleableState.On
                CheckState.Partial -> ToggleableState.Indeterminate
                CheckState.Unchecked -> ToggleableState.Off
            },
            onClick = onToggleSelected,
            enabled = row.state != CheckState.Covered,
        )
        Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}
