// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.albums

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.github.libregeek.fernix.playback.MediaItems
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.RowMenu

@Composable
fun AlbumDetailScreen(
    albumId: Long,
    db: FernixDatabase,
    playback: PlaybackController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: AlbumDetailViewModel = viewModel(
        key = "album$albumId",
        factory = viewModelFactory { initializer { AlbumDetailViewModel(albumId, db, playback) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(listState, state.tracks.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.tracks.size - LOAD_AHEAD) vm.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            val album = state.album
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    album?.album ?: stringResource(R.string.album_unknown),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (album != null && album.year != 0) {
                    Text(album.year.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.album != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::playAll) { Text(stringResource(R.string.play)) }
                OutlinedButton(onClick = vm::enqueueAll) { Text(stringResource(R.string.add_to_queue)) }
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.tracks, key = { it.id }) { track ->
                    ListItem(
                        modifier = Modifier.clickable { vm.playFrom(track) },
                        headlineContent = { Text(track.title.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = MediaItems.display(track.artist)?.let {
                            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        trailingContent = {
                            RowMenu(onPlay = { vm.playFrom(track) }, onEnqueue = { vm.enqueueTrack(track) })
                        },
                    )
                }
            }
        }
    }
}

private const val LOAD_AHEAD = 20
