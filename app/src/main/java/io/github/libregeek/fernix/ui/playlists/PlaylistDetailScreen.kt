// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.PlaylistEntryRow
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.MediaItems
import io.github.libregeek.fernix.playback.PlaybackController

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    db: FernixDatabase,
    search: TrackSearch,
    playback: PlaybackController,
    onBack: () -> Unit,
    onOpenTrack: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: PlaylistDetailViewModel = viewModel(
        key = "playlist$playlistId",
        factory = viewModelFactory { initializer { PlaylistDetailViewModel(playlistId, db, search, playback) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(listState, state.entries.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.entries.size - LOAD_AHEAD) vm.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            Text(
                when {
                    DefaultPlaylists.isReadOnly(playlistId) -> stringResource(DefaultPlaylists.title(playlistId))
                    state.isFavourites -> stringResource(R.string.playlist_favourites)
                    else -> state.name ?: stringResource(R.string.album_unknown)
                },
                modifier = Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.exists) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.play() }, enabled = !state.empty) { Text(stringResource(R.string.play)) }
                OutlinedButton(onClick = { vm.play(shuffle = true) }, enabled = !state.empty) {
                    Text(stringResource(R.string.playlist_shuffle))
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (state.empty) {
                Text(
                    stringResource(R.string.playlist_empty),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.entries, key = { it.position }) { entry ->
                    val track = entry.track
                    ListItem(
                        modifier = Modifier.clickable { vm.play(position = entry.position) },
                        headlineContent = { Text(track.title.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = MediaItems.display(track.artist)?.let {
                            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        trailingContent = {
                            EntryMenu(
                                onPlay = { vm.play(position = entry.position) },
                                onEnqueue = { vm.enqueue(entry) },
                                onRemove = if (state.readOnly) null else ({ vm.remove(entry) }),
                                onDetails = { onOpenTrack(track.id) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryMenu(onPlay: () -> Unit, onEnqueue: () -> Unit, onRemove: (() -> Unit)?, onDetails: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, onClick = { open = false; onPlay() })
            DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = { open = false; onEnqueue() })
            DropdownMenuItem(text = { Text(stringResource(R.string.track_details)) }, onClick = { open = false; onDetails() })
            if (onRemove != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_remove_entry)) },
                    onClick = { open = false; onRemove() },
                )
            }
        }
    }
}

private const val LOAD_AHEAD = 20
