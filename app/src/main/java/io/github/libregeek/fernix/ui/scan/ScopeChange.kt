// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.scan.MediaSource
import io.github.libregeek.fernix.data.scan.ScanScope
import io.github.libregeek.fernix.data.scan.ScopeImpact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The way every change of the scan folders is made (spec/architecture.md §6 *Changing the scope*): the
 * returned function takes the new scope; if it would remove tracks, a dialog says how many (and how many
 * playlist entries go with them) and waits for a confirmation, otherwise [onApply] runs at once. The
 * dialog is part of this composable's output. The pending scope survives rotation and the process being
 * killed, kept as plain strings.
 */
@Composable
fun rememberScopeChange(db: FernixDatabase, media: MediaSource, onApply: (ScanScope) -> Unit): (ScanScope) -> Unit {
    val coroutines = rememberCoroutineScope()
    var pendingRaw by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val pending = pendingRaw?.let(::decodePending)

    pending?.let { (chosen, impact) ->
        AlertDialog(
            onDismissRequest = { pendingRaw = null },
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
                TextButton(onClick = { pendingRaw = null; onApply(chosen) }) { Text(stringResource(R.string.scope_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingRaw = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    return remember(db, media, onApply) {
        { chosen: ScanScope ->
            coroutines.launch {
                val impact = withContext(Dispatchers.IO) { ScopeImpact.compute(db, chosen, media.mountedVolumes()) }
                if (impact.isEmpty) onApply(chosen) else pendingRaw = encodePending(chosen, impact)
            }
            Unit
        }
    }
}

/** The scope and its impact as strings: the two counts, then the folders. */
internal fun encodePending(scope: ScanScope, impact: ScopeImpact): List<String> = buildList {
    add(impact.tracks.toString())
    add(impact.playlistEntries.toString())
    addAll(scope.folders)
}

internal fun decodePending(raw: List<String>): Pair<ScanScope, ScopeImpact> =
    ScanScope(raw.drop(2).toSet()) to ScopeImpact(raw[0].toInt(), raw[1].toInt())
