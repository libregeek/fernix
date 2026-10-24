// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.tracks

import io.github.libregeek.fernix.data.db.TrackRow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.libregeek.fernix.ui.common.AddToPlaylistSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import io.github.libregeek.fernix.ui.common.CompactSearchField
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import io.github.libregeek.fernix.tags.TagValues
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.libregeek.fernix.data.db.TrackResult
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.MediaItems
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.RowMenu
import io.github.libregeek.fernix.ui.common.Thumbnail
import io.github.libregeek.fernix.ui.common.trackThumbnail

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracksScreen(
    db: FernixDatabase,
    search: TrackSearch,
    playback: PlaybackController,
    personId: Long = 0,
    tagKeyId: Long = 0,
    onOpenTrack: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: TracksViewModel = viewModel(
        key = "tracks$personId-$tagKeyId",
        factory = viewModelFactory { initializer { TracksViewModel(db, search, playback, personId, tagKeyId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var addTracks by remember { mutableStateOf<(suspend () -> List<TrackRow>)?>(null) }

    LaunchedEffect(listState, state.results.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.results.size - LOAD_AHEAD) vm.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        // The search box and the filters live in one container; the icon at its bottom resets all of them.
        OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactSearchField(state.text, vm::setText, stringResource(R.string.search_hint))
                // The chips wrap onto more lines instead of scrolling away.
                if (state.person != null || state.chips.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.person?.let { person ->
                            FilterChipItem(person.name) { vm.setPerson(null) }
                        }
                        state.chips.forEach { chip ->
                            FilterChipItem("${chip.key}: ${chip.value}") { vm.removeChip(chip) }
                        }
                    }
                }
                // The bottom of the container: add a filter on the left, reset everything on the right.
                // Neither moves with the chips above, so both are always in view.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = vm::openKeyPicker) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.search_add_filter))
                    }
                    if (state.hasFilter) {
                        IconButton(onClick = vm::clearAll) {
                            Icon(painterResource(R.drawable.ic_clear_all), stringResource(R.string.search_reset))
                        }
                    }
                }
            }
        }
        if (state.results.isNotEmpty()) {
            // One capsule of actions on every track matching the search and filters (not only the loaded rows).
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::playAll) {
                        Icon(painterResource(R.drawable.ic_play), stringResource(R.string.play))
                    }
                    IconButton(onClick = vm::enqueueAll) {
                        Icon(painterResource(R.drawable.ic_queue_add), stringResource(R.string.add_to_queue))
                    }
                    // To one of your playlists, Favourites, or a new playlist made from the results.
                    IconButton(onClick = { addTracks = vm::allTracks }) {
                        Icon(painterResource(R.drawable.ic_playlist_add), stringResource(R.string.add_to_playlist))
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (state.noResults) {
                Text(
                    stringResource(if (state.hasFilter) R.string.search_no_results else R.string.tracks_empty),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.results, key = { it.track.id }) { result ->
                    ResultRow(
                        result,
                        onPlay = { vm.playFrom(result.track) },
                        onEnqueue = { vm.enqueueTrack(result.track) },
                        onAddToPlaylist = { addTracks = { listOf(result.track) } },
                        onDetails = { onOpenTrack(result.track.id) },
                    )
                }
            }
        }
    }

    state.keyChoices?.let { keys ->
        AlertDialog(
            onDismissRequest = vm::dismissPickers,
            title = { Text(stringResource(R.string.search_pick_key)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(keys, key = { it.id }) { key ->
                        ListItem(modifier = Modifier.clickable { vm.pickKey(key) }, headlineContent = { Text(key.key) })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissPickers) { Text(stringResource(R.string.cancel)) } },
        )
    }

    state.valuePicker?.let { picker ->
        val pickerState = rememberLazyListState()
        LaunchedEffect(pickerState, picker.values.size) {
            snapshotFlow { pickerState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { last -> if (last >= picker.values.size - LOAD_AHEAD) vm.loadMoreValues() }
        }
        AlertDialog(
            onDismissRequest = vm::dismissPickers,
            title = { Text(stringResource(R.string.search_pick_value, picker.key.key)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = picker.prefix,
                        onValueChange = vm::setValuePrefix,
                        placeholder = { Text(stringResource(R.string.search_value_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!picker.loading && picker.values.isEmpty()) {
                        Text(stringResource(R.string.search_no_values), modifier = Modifier.padding(top = 12.dp))
                    }
                    LazyColumn(state = pickerState, modifier = Modifier.heightIn(max = 300.dp)) {
                        items(picker.values, key = { it.valueId }) { value ->
                            ListItem(
                                modifier = Modifier.clickable { vm.pickValue(value) },
                                headlineContent = { Text(value.value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Text(value.trackCount.toString()) },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissPickers) { Text(stringResource(R.string.cancel)) } },
        )
    }
    addTracks?.let { AddToPlaylistSheet(db, it) { addTracks = null } }
}

/** A removable filter chip; a long label is cut with an ellipsis instead of pushing the others away. */
@Composable
private fun FilterChipItem(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        modifier = Modifier.widthIn(max = 240.dp),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.search_remove_filter), Modifier.size(18.dp)) },
    )
}

/**
 * A track row (spec/architecture.md §9.3): the title; below it the album with its date in brackets,
 * smaller; below that the artists, smaller. With a person filter the person's roles follow.
 */
@Composable
private fun ResultRow(
    result: TrackResult,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDetails: () -> Unit,
) {
    val track = result.track
    val album = trackAlbumLine(track.album, track.date)
    val artists = MediaItems.display(track.artist)
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        leadingContent = { Thumbnail(trackThumbnail(track.albumId, track.mediaStoreId)) },
        headlineContent = { Text(track.title.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (album == null && artists == null && result.roles.isEmpty()) {
            null
        } else {
            {
                Column {
                    album?.let { SmallLine(it) }
                    artists?.let { SmallLine(it) }
                    if (result.roles.isNotEmpty()) SmallLine(result.roles.joinToString(", "), MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailingContent = { RowMenu(onPlay = onPlay, onEnqueue = onEnqueue, onAddToPlaylist = onAddToPlaylist, onDetails = onDetails) },
    )
}

@Composable
private fun SmallLine(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** `Album (1996)`; the album alone, or just `(1996)`, when the other is missing; null when both are. */
internal fun trackAlbumLine(album: String, date: String): String? {
    val name = MediaItems.display(album)
    val first = TagValues.split(date).firstOrNull()
    return when {
        name != null && first != null -> "$name ($first)"
        name != null -> name
        first != null -> "($first)"
        else -> null
    }
}

private const val LOAD_AHEAD = 20
