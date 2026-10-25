// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.scan.MediaSource
import io.github.libregeek.fernix.data.scan.ScanScope

/**
 * **Library folders** (spec/architecture.md §6, §9): the folders Fernix scans, each with a remove button,
 * and **Add folders**, which opens the in-app chooser with the current folders ticked. Every change goes
 * through [rememberScopeChange]: one that would remove tracks says how many and waits for a confirmation;
 * applying saves the folders and rescans. Removing the last folder is allowed and empties the library.
 */
@Composable
fun LibraryFoldersScreen(
    db: FernixDatabase,
    media: MediaSource,
    settings: Settings,
    onApply: (ScanScope) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    // The saved folders are not observable; read them again after each applied change.
    var applied by remember { mutableIntStateOf(0) }
    val change = rememberScopeChange(db, media) { onApply(it); applied++ }
    val folders = remember(applied) { settings.scanScope?.folders.orEmpty().sorted() }

    if (choosing) {
        // Ticking a folder adds it (with its sub-folders; folders it covers fold into it); unticking removes it.
        FolderChooserScreen(
            initialSelection = folders.toSet(),
            onDone = { choosing = false; change(ScanScope(it)) },
            onCancel = { choosing = false },
            media = media,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            Text(stringResource(R.string.settings_library_folders), style = MaterialTheme.typography.titleMedium)
        }
        if (folders.isEmpty()) {
            Text(stringResource(R.string.scope_none), modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            items(folders, key = { it }) { path ->
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { change(ScanScope(folders.toSet() - path)) }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.scope_remove_folder))
                    }
                }
            }
        }
        Button(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.scope_add)) }
    }
}
