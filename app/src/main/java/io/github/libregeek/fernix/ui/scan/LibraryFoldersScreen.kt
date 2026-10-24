// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.scan.MediaSource
import io.github.libregeek.fernix.data.scan.ScanScope
import io.github.libregeek.fernix.data.scan.ScopeImpact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Library folders** (spec/architecture.md §6, §9): the same choice as on first launch, started from the
 * current scope. A scope that would remove tracks first says how many, and how many playlist entries go
 * with them, and waits for a confirmation; a wider or equal one is applied at once. Applying saves the
 * scope and rescans.
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
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<ScanScope, ScopeImpact>?>(null) }

    // Inside the app shell: the padding keeps the buttons clear of the bottom bars.
    Box(modifier) {
        ScanScopeScreen(
            initial = settings.scanScope,
            onCancel = onDone,
            onChosen = { chosen ->
                scope.launch {
                    val impact = withContext(Dispatchers.IO) { ScopeImpact.compute(db, chosen, media.mountedVolumes()) }
                    if (impact.isEmpty) {
                        onApply(chosen)
                        onDone()
                    } else {
                        pending = chosen to impact
                    }
                }
            },
        )
    }

    pending?.let { (chosen, impact) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.scope_remove_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (impact.tracks > 0) Text(pluralStringResource(R.plurals.scope_remove_tracks, impact.tracks, impact.tracks))
                    if (impact.playlistEntries > 0) {
                        Text(pluralStringResource(R.plurals.scope_remove_entries, impact.playlistEntries, impact.playlistEntries))
                    }
                    Text(stringResource(R.string.scope_remove_note))
                }
            },
            confirmButton = {
                TextButton(onClick = { pending = null; onApply(chosen); onDone() }) {
                    Text(stringResource(R.string.scope_remove_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
