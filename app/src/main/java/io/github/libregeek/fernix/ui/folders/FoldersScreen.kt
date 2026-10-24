// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.folders

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.libregeek.fernix.ui.common.AddToPlaylistDialog
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import io.github.libregeek.fernix.data.db.FolderEntity
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.data.scan.ScanState
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.RowMenu
import io.github.libregeek.fernix.ui.common.ListMenu
import io.github.libregeek.fernix.ui.split.SplitTagsDialog

@Composable
fun FoldersScreen(
    db: FernixDatabase,
    playback: PlaybackController,
    onOpenTrack: (Long) -> Unit,
    scanState: ScanState,
    modifier: Modifier = Modifier,
) {
    val vm: FoldersViewModel = viewModel(
        factory = viewModelFactory { initializer { FoldersViewModel(db, playback) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var splitting by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var addTracks by remember { mutableStateOf<(suspend () -> List<TrackRow>)?>(null) }

    BackHandler(enabled = state.canGoUp) { vm.up() }

    // The library changes under a running scan: show new music while it runs (only while there is
    // nothing to show, so the list doesn't jump) and again when it has finished.
    LaunchedEffect(scanState) {
        if (scanState is ScanState.Finished || (scanState is ScanState.Running && state.empty)) vm.refresh()
    }

    // Next page when the last loaded row comes into view.
    LaunchedEffect(listState, state.folders.size, state.tracks.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (last >= state.folders.size + state.tracks.size - LOAD_AHEAD) vm.loadMore()
            }
    }
    // Back at the top when the folder changes.
    LaunchedEffect(state.path) { listState.scrollToItem(0) }

    Box(modifier.fillMaxSize()) {
        if (state.empty) {
            Text(
                stringResource(R.string.folders_empty),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (state.path.isNotEmpty()) {
                item(key = "header") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.canGoUp) {
                            IconButton(onClick = { vm.up() }) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.folder_up))
                            }
                        }
                        Text(
                            state.path.last().displayName(),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.tracks.isNotEmpty()) ListMenu(onSplitTags = { splitting = true })
                    }
                }
            }
            items(state.folders, key = { "f${it.id}" }) { folder ->
                ListItem(
                    modifier = Modifier.clickable { vm.open(folder) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_folder), null) },
                    headlineContent = { Text(folder.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        RowMenu(
                            onPlay = { vm.playFolder(folder) },
                            onEnqueue = { vm.enqueueFolder(folder) },
                            onAddToPlaylist = { addTracks = { vm.subtree(folder) } },
                        )
                    },
                )
            }
            items(state.tracks, key = { "t${it.id}" }) { track ->
                ListItem(
                    modifier = Modifier.clickable { vm.playTrack(track) },
                    headlineContent = { Text(track.title.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = track.subtitle()?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = {
                        RowMenu(
                            onPlay = { vm.playTrack(track) },
                            onEnqueue = { vm.enqueueTrack(track) },
                            onAddToPlaylist = { addTracks = { listOf(track) } },
                            onDetails = { onOpenTrack(track.id) },
                        )
                    },
                )
            }
        }
    }
    addTracks?.let { AddToPlaylistDialog(db, it) { addTracks = null } }
    if (splitting) SplitTagsDialog(db, trackIds = vm::trackIds) { splitting = false }
}

@Composable
private fun FolderEntity.displayName(): String =
    if (parentId == null && name == MediaStore.VOLUME_EXTERNAL_PRIMARY) stringResource(R.string.volume_internal) else name

private fun TrackRow.subtitle(): String? =
    listOf(artist, album).map { io.github.libregeek.fernix.playback.MediaItems.display(it) }
        .filterNotNull().joinToString(" · ").ifEmpty { null }

private const val LOAD_AHEAD = 20
