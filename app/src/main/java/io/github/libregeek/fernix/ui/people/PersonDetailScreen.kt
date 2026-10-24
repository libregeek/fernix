// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.people

import io.github.libregeek.fernix.data.db.TrackRow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.libregeek.fernix.ui.common.AddToPlaylistSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.ui.art.PersonArt
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.ListActions
import io.github.libregeek.fernix.ui.common.RolePill
import io.github.libregeek.fernix.ui.common.RowMenu
import io.github.libregeek.fernix.ui.tracks.trackAlbumLine

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonDetailScreen(
    personId: Long,
    db: FernixDatabase,
    search: TrackSearch,
    playback: PlaybackController,
    onBack: () -> Unit,
    onOpenTrack: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: PersonDetailViewModel = viewModel(
        key = "person$personId",
        factory = viewModelFactory { initializer { PersonDetailViewModel(personId, db, search, playback) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var addTracks by remember { mutableStateOf<(suspend () -> List<TrackRow>)?>(null) }

    LaunchedEffect(listState, state.tracks.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.tracks.size - LOAD_AHEAD) vm.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            val name = state.name
            // No image support yet: initials stand in for the picture (spec §7 "Person images").
            Initials(personId, name.orEmpty())
            Text(
                name ?: stringResource(R.string.album_unknown),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The roles and the actions are items of the list, so in landscape everything scrolls together.
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (state.name != null) {
                if (state.roles.isNotEmpty()) {
                    item(key = "roles") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.person_roles), style = MaterialTheme.typography.labelMedium)
                            // Each role as a small outlined pill, with the number of tracks in it.
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                state.roles.forEach { role -> RolePill("${role.key} · ${role.trackCount}") }
                            }
                        }
                    }
                }
                item(key = "actions") {
                    // Every track of the person, not only the loaded rows: the same three icons as on Tracks.
                    ListActions(onPlay = vm::playAll, onEnqueue = vm::enqueueAll, onAddToPlaylist = { addTracks = vm::allTracks })
                }
            }
            items(state.tracks, key = { it.track.id }) { result ->
                val track = result.track
                ListItem(
                    modifier = Modifier.clickable { vm.playFrom(track) },
                    headlineContent = { Text(track.title.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // The album with its date in brackets, as on Tracks, then the person's roles on the track.
                            trackAlbumLine(track.album, track.date)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (result.roles.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    result.roles.forEach { RolePill(it) }
                                }
                            }
                        }
                    },
                    trailingContent = {
                        RowMenu(
                            onPlay = { vm.playFrom(track) },
                            onEnqueue = { vm.enqueueTrack(track) },
                            onAddToPlaylist = { addTracks = { listOf(track) } },
                            onDetails = { onOpenTrack(track.id) },
                        )
                    },
                )
            }
        }
    }
    addTracks?.let { AddToPlaylistSheet(db, it) { addTracks = null } }
}

@Composable
private fun Initials(personId: Long, name: String) {
    val initials = name.split(' ').filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = MaterialTheme.colorScheme.onSecondaryContainer)
        // A role-typed embedded picture of this person, if any of their first tracks has one.
        if (name.isNotEmpty()) {
            AsyncImage(
                model = PersonArt(personId, name),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private const val LOAD_AHEAD = 20
