// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.albums

import io.github.libregeek.fernix.data.db.TrackRow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.libregeek.fernix.ui.common.AddToPlaylistSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.libregeek.fernix.ui.common.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.libregeek.fernix.data.scan.ScanState
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.art.AlbumThumbnail
import io.github.libregeek.fernix.ui.common.RowMenu
import io.github.libregeek.fernix.ui.common.Thumbnail

@Composable
fun AlbumsScreen(
    db: FernixDatabase,
    playback: PlaybackController,
    onOpenAlbum: (Long) -> Unit,
    onSettings: () -> Unit,
    scanState: ScanState,
    modifier: Modifier = Modifier,
) {
    val vm: AlbumsViewModel = viewModel(
        factory = viewModelFactory { initializer { AlbumsViewModel(db, playback, createSavedStateHandle()) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(scanState) {
        if (scanState is ScanState.Finished || (scanState is ScanState.Running && state.empty)) vm.refresh()
    }
    var addTracks by remember { mutableStateOf<(suspend () -> List<TrackRow>)?>(null) }

    LaunchedEffect(listState, state.albums.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.albums.size - LOAD_AHEAD) vm.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        LibraryTopBar(
            stringResource(R.string.tab_albums),
            onSettings,
            query = state.query,
            onQuery = vm::setQuery,
            hint = stringResource(R.string.search_albums_hint),
        )
        Box(Modifier.fillMaxSize()) {
            if (state.empty) {
                Text(
                    stringResource(if (state.query.isEmpty()) R.string.albums_empty else R.string.albums_no_match),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.albums, key = { it.id }) { album ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenAlbum(album.id) },
                        leadingContent = { Thumbnail(AlbumThumbnail(album.id)) },
                        headlineContent = { Text(album.album, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        // The year (when known) and the number of tracks.
                        supportingContent = {
                            val tracks = pluralStringResource(R.plurals.track_count, album.trackCount, album.trackCount)
                            Text(if (album.year != 0) "${album.year} · $tracks" else tracks)
                        },
                        trailingContent = {
                            RowMenu(
                                onPlay = { vm.play(album) },
                                onEnqueue = { vm.enqueue(album) },
                                onAddToPlaylist = { addTracks = { vm.tracksOf(album) } },
                            )
                        },
                    )
                }
            }
    
        }
    }
    addTracks?.let { AddToPlaylistSheet(db, it) { addTracks = null } }
}

private const val LOAD_AHEAD = 20
