// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.albums

import io.github.libregeek.fernix.data.db.TrackRow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.libregeek.fernix.ui.common.AddToPlaylistDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.scan.ScanState
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.RowMenu

@Composable
fun AlbumsScreen(
    db: FernixDatabase,
    playback: PlaybackController,
    onOpenAlbum: (Long) -> Unit,
    scanState: ScanState,
    modifier: Modifier = Modifier,
) {
    val vm: AlbumsViewModel = viewModel(
        factory = viewModelFactory { initializer { AlbumsViewModel(db, playback) } },
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

    Box(modifier.fillMaxSize()) {
        if (state.empty) {
            Text(
                stringResource(R.string.albums_empty),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.albums, key = { it.id }) { album ->
                ListItem(
                    modifier = Modifier.clickable { onOpenAlbum(album.id) },
                    headlineContent = { Text(album.album, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (album.year != 0) {
                        { Text(album.year.toString()) }
                    } else {
                        null
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
    addTracks?.let { AddToPlaylistDialog(db, it) { addTracks = null } }
}

private const val LOAD_AHEAD = 20
