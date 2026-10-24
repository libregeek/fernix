// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PlaylistEntity
import io.github.libregeek.fernix.data.db.PlaylistRow
import io.github.libregeek.fernix.data.db.TrackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks a playlist (or creates one) and appends the tracks from [tracks] to it. [tracks] is
 * resolved only after a choice, so a folder or album isn't loaded just to open the dialog.
 */
@Composable
fun AddToPlaylistDialog(db: FernixDatabase, tracks: suspend () -> List<TrackRow>, onDone: () -> Unit) {
    var playlists by remember { mutableStateOf<List<PlaylistRow>?>(null) }
    val favouritesId by produceState<Long?>(null) { value = db.playlistDao().favouritesId() }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Favourites first, then the user's own playlists; the read-only defaults are never offered.
        val dao = db.playlistDao()
        playlists = listOfNotNull(dao.favouritesSummary().first()) + dao.summaries().first()
    }

    fun addTo(playlistId: Long) {
        scope.launch {
            val rows = tracks()
            withContext(Dispatchers.IO) {
                db.playlistDao().append(playlistId, rows.map { it.id }, System.currentTimeMillis())
            }
            onDone()
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            onConfirm = { name ->
                scope.launch {
                    val now = System.currentTimeMillis()
                    val id = withContext(Dispatchers.IO) {
                        db.playlistDao().insert(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
                    }
                    addTo(id)
                }
            },
            onDismiss = { creating = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { creating = true },
                        headlineContent = { Text(stringResource(R.string.playlist_new)) },
                    )
                }
                items(playlists.orEmpty(), key = { it.id }) { playlist ->
                    ListItem(
                        modifier = Modifier.clickable { addTo(playlist.id) },
                        headlineContent = {
                            Text(
                                if (playlist.id == favouritesId) stringResource(R.string.playlist_favourites) else playlist.name,
                            )
                        },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount))
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}
