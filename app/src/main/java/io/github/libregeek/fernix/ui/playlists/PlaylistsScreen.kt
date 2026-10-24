// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PlaylistRow
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.NameDialog
import io.github.libregeek.fernix.ui.common.RowMenu

@Composable
fun PlaylistsScreen(
    db: FernixDatabase,
    search: TrackSearch,
    playback: PlaybackController,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: PlaylistsViewModel = viewModel(
        factory = viewModelFactory { initializer { PlaylistsViewModel(db, search, playback) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistRow?>(null) }
    var deleting by remember { mutableStateOf<PlaylistRow?>(null) }

    Column(modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = { creating = true },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.playlist_new)) }
        LazyColumn(Modifier.fillMaxSize()) {
            // The five default playlists come first; only Favourites among them can be edited,
            // and not renamed or deleted either, so none of them has a rename/delete menu.
            state?.favourites?.let { favourites ->
                item(key = "favourites") {
                    ListItem(
                        modifier = Modifier.clickable { onOpenPlaylist(favourites.id) },
                        headlineContent = { Text(stringResource(R.string.playlist_favourites)) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.track_count, favourites.trackCount, favourites.trackCount))
                        },
                        trailingContent = {
                            RowMenu(onPlay = { vm.play(favourites.id) }, onEnqueue = { vm.enqueue(favourites.id) })
                        },
                    )
                }
            }
            items(DefaultPlaylists.ReadOnly, key = { "default$it" }) { id ->
                ListItem(
                    modifier = Modifier.clickable { onOpenPlaylist(id) },
                    headlineContent = { Text(stringResource(DefaultPlaylists.title(id))) },
                    supportingContent = { Text(stringResource(DefaultPlaylists.description(id))) },
                    trailingContent = { RowMenu(onPlay = { vm.play(id) }, onEnqueue = { vm.enqueue(id) }) },
                )
            }
            val own = state?.playlists.orEmpty()
            if (state != null && own.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.playlists_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(own, key = { it.id }) { playlist ->
                ListItem(
                    modifier = Modifier.clickable { onOpenPlaylist(playlist.id) },
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount))
                    },
                    trailingContent = {
                        PlaylistMenu(
                            onPlay = { vm.play(playlist.id) },
                            onEnqueue = { vm.enqueue(playlist.id) },
                            onRename = { renaming = playlist },
                            onDelete = { deleting = playlist },
                        )
                    },
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            onConfirm = { vm.create(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { playlist ->
        NameDialog(
            title = stringResource(R.string.playlist_rename),
            confirmLabel = stringResource(R.string.save),
            initial = playlist.name,
            onConfirm = { vm.rename(playlist.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.playlist_delete_confirm, playlist.name)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(playlist.id); deleting = null }) {
                    Text(stringResource(R.string.playlist_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PlaylistMenu(onPlay: () -> Unit, onEnqueue: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, onClick = { open = false; onPlay() })
            DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = { open = false; onEnqueue() })
            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_rename)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.playlist_delete)) }, onClick = { open = false; onDelete() })
        }
    }
}
