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
import io.github.libregeek.fernix.ui.common.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.ui.common.LibraryTopBar
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PlaylistRow
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.NameSheet
import io.github.libregeek.fernix.ui.common.RowMenu

@Composable
fun PlaylistsScreen(
    db: FernixDatabase,
    search: TrackSearch,
    playback: PlaybackController,
    onOpenPlaylist: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: PlaylistsViewModel = viewModel(
        factory = viewModelFactory { initializer { PlaylistsViewModel(db, search, playback, createSavedStateHandle()) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // Kept across rotation and the process being killed: the dialogs are remembered by playlist id.
    var creating by rememberSaveable { mutableStateOf(false) }
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val renaming = state?.playlists?.firstOrNull { it.id == renamingId }
    val deleting = state?.playlists?.firstOrNull { it.id == deletingId }

    val query by vm.query.collectAsStateWithLifecycle()
    // The playlists whose name contains what is typed (the default ones by their shown names).
    val favouritesName = stringResource(R.string.playlist_favourites)
    val shownDefaults = DefaultPlaylists.ReadOnly.filter { stringResource(DefaultPlaylists.title(it)).contains(query, ignoreCase = true) }

    Column(modifier.fillMaxSize()) {
        LibraryTopBar(
            stringResource(R.string.tab_playlists),
            onSettings,
            query = query,
            onQuery = vm::setQuery,
            hint = stringResource(R.string.search_playlists_hint),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            // The five default playlists come first; only Favourites among them can be edited,
            // and not renamed or deleted either, so none of them has a rename/delete menu.
            state?.favourites?.takeIf { favouritesName.contains(query, ignoreCase = true) }?.let { favourites ->
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
            items(shownDefaults, key = { "default$it" }) { id ->
                ListItem(
                    modifier = Modifier.clickable { onOpenPlaylist(id) },
                    headlineContent = { Text(stringResource(DefaultPlaylists.title(id))) },
                    supportingContent = { Text(stringResource(DefaultPlaylists.description(id))) },
                    trailingContent = { RowMenu(onPlay = { vm.play(id) }, onEnqueue = { vm.enqueue(id) }) },
                )
            }
            val own = state?.playlists.orEmpty().filter { it.name.contains(query, ignoreCase = true) }
            if (state != null && own.isEmpty() && (query.isEmpty() || (shownDefaults.isEmpty() && !favouritesName.contains(query, ignoreCase = true)))) {
                item(key = "none") {
                    Text(
                        stringResource(if (query.isEmpty()) R.string.playlists_empty else R.string.playlists_no_match),
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
                            onRename = { renamingId = playlist.id },
                            onDelete = { deletingId = playlist.id },
                        )
                    },
                )
            }
            item(key = "new") {
                // After the last playlist: an icon, not a button.
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    FilledTonalIconButton(onClick = { creating = true }) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.playlist_new))
                    }
                }
            }
        }
    }

    if (creating) {
        NameSheet(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            onConfirm = { vm.create(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { playlist ->
        NameSheet(
            title = stringResource(R.string.playlist_rename),
            confirmLabel = stringResource(R.string.save),
            initial = playlist.name,
            onConfirm = { vm.rename(playlist.id, it); renamingId = null },
            onDismiss = { renamingId = null },
        )
    }
    deleting?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            text = { Text(stringResource(R.string.playlist_delete_confirm, playlist.name)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(playlist.id); deletingId = null }) {
                    Text(stringResource(R.string.playlist_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.cancel)) } },
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
