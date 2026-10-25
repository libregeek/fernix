// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.tracks

import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.data.scan.ScanState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.libregeek.fernix.ui.common.AddToPlaylistSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import io.github.libregeek.fernix.ui.common.CompactSearchField
import io.github.libregeek.fernix.ui.common.ListActions
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
import io.github.libregeek.fernix.ui.common.ListItem
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.ui.common.unavailableOverline
import io.github.libregeek.fernix.ui.common.isUnavailable
import io.github.libregeek.fernix.ui.common.dimmedIf
import io.github.libregeek.fernix.ui.common.LibraryTopBar
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.data.db.TrackSort
import io.github.libregeek.fernix.data.db.TrackResult
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.MediaItems
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.RowMenu
import io.github.libregeek.fernix.ui.common.Thumbnail
import io.github.libregeek.fernix.ui.common.trackThumbnail

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TracksScreen(
    db: FernixDatabase,
    search: TrackSearch,
    playback: PlaybackController,
    onOpenTrack: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
    personId: Long = 0,
    tagKeyId: Long = 0,
    onSettings: () -> Unit = {},
    scanState: ScanState = ScanState.Idle,
    settings: Settings,
) {
    val vm: TracksViewModel = viewModel(
        key = "tracks$personId-$tagKeyId",
        factory = viewModelFactory { initializer { TracksViewModel(db, search, playback, createSavedStateHandle(), settings, personId, tagKeyId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // The library changes under a running scan: show new tracks while it runs (only while there is nothing
    // to show, so the list doesn't jump) and again when it has finished, so the first launch ends on a full list.
    LaunchedEffect(scanState) {
        if (scanState is ScanState.Finished || (scanState is ScanState.Running && state.results.isEmpty())) vm.refresh()
    }
    var addTracks by remember { mutableStateOf<(suspend () -> List<TrackRow>)?>(null) }

    LaunchedEffect(listState, state.results.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.results.size - LOAD_AHEAD) vm.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        LibraryTopBar(
            stringResource(R.string.tab_tracks),
            onSettings,
            query = state.text,
            onQuery = vm::setText,
            hint = stringResource(R.string.search_hint),
            actions = {
                IconButton(onClick = vm::openKeyPicker) {
                    Icon(painterResource(R.drawable.ic_filter), stringResource(R.string.search_add_filter))
                }
            },
        )
        // One list for everything, so in landscape the filters, the actions and the results scroll together.
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (state.person != null || state.chips.isNotEmpty()) {
                item(key = "filters") {
                    // The filters wrap onto more lines instead of scrolling away; reset clears them and the text.
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
                        IconButton(onClick = vm::clearAll) {
                            Icon(painterResource(R.drawable.ic_clear_all), stringResource(R.string.search_reset))
                        }
                    }
                }
            }
            if (state.results.isNotEmpty()) {
                item(key = "actions") {
                    // Acts on every track matching the search and filters (not only the loaded rows); Add to
                    // playlist offers your playlists, Favourites, or a new playlist made from them.
                    ListActions(
                        onPlay = vm::playAll,
                        onEnqueue = vm::enqueueAll,
                        onAddToPlaylist = { addTracks = vm::allTracks },
                        onSort = vm::openSort,
                        sortActive = !state.sort.isDefault,
                    )
                }
                if (!state.sort.isDefault) {
                    item(key = "sort") {
                        // The order in force; tap it to change it, the cross goes back to title order.
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.sort_sorted_by, state.sort.summary()),
                                modifier = Modifier.weight(1f).clickable(onClick = vm::openSort),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { vm.setSort(TrackSort.DEFAULT) }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.sort_clear))
                            }
                        }
                    }
                }
            }
            if (state.noResults) {
                item(key = "none") {
                    Text(
                        stringResource(if (state.hasFilter) R.string.search_no_results else R.string.tracks_empty),
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            items(state.results, key = { it.track.id }) { result ->
                ResultRow(
                    result,
                    onPlay = { vm.playFrom(result.track) },
                    onEnqueue = { vm.enqueueTrack(result.track) },
                    onAddToPlaylist = { addTracks = { listOf(result.track) } },
                    onDetails = { onOpenTrack(result.track.id) },
                    onOpenAlbum = result.track.albumId?.let { id -> { onOpenAlbum(id) } },
                )
            }
        }
    }

    if (state.sortOpen) SortSheet(state.sort, onApply = vm::setSort, onDismiss = vm::closeSort)

    // Adding a filter: one bottom sheet, first the tag, then its value (the arrow goes back to the tags).
    if (state.keyChoices != null || state.valuePicker != null) {
        ModalBottomSheet(onDismissRequest = vm::dismissPickers, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.navigationBarsPadding().fillMaxHeight(0.7f)) {
                val picker = state.valuePicker
                if (picker == null) {
                    Text(
                        stringResource(R.string.search_pick_key),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.keyChoices.orEmpty(), key = { it.id }) { key ->
                            ListItem(
                                modifier = Modifier.clickable { vm.pickKey(key) },
                                headlineContent = { Text(key.key) },
                            )
                        }
                    }
                } else {
                    val pickerState = rememberLazyListState()
                    LaunchedEffect(pickerState, picker.values.size) {
                        snapshotFlow { pickerState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                            .collect { last -> if (last >= picker.values.size - LOAD_AHEAD) vm.loadMoreValues() }
                    }
                    Row(Modifier.padding(start = 8.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = vm::openKeyPicker) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
                        }
                        Text(picker.key.key, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CompactSearchField(
                        picker.prefix,
                        vm::setValuePrefix,
                        stringResource(R.string.search_value_hint),
                        Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    if (!picker.loading && picker.values.isEmpty()) {
                        Text(stringResource(R.string.search_no_values), modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    }
                    LazyColumn(state = pickerState, modifier = Modifier.weight(1f)) {
                        items(picker.values, key = { it.valueId }) { value ->
                            ListItem(
                                modifier = Modifier.clickable { vm.pickValue(value) },
                                headlineContent = { Text(value.value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Text(value.trackCount.toString()) },
                            )
                        }
                    }
                }
            }
        }
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
    /** Opens the album details; null for a track with no album. */
    onOpenAlbum: (() -> Unit)?,
) {
    val track = result.track
    val album = trackAlbumLine(track.album, track.date)
    val artists = MediaItems.display(track.artist)
    val gone = track.isUnavailable()
    ListItem(
        modifier = Modifier.dimmedIf(gone).clickable(onClick = onPlay),
        overlineContent = unavailableOverline(gone),
        leadingContent = { Thumbnail(trackThumbnail(track.albumId, track.mediaStoreId)) },
        headlineContent = { Text(track.title.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (album == null && artists == null && result.roles.isEmpty()) {
            null
        } else {
            {
                Column {
                    // The album line has its own tap (the album details); the rest of the row plays.
                    album?.let { if (onOpenAlbum != null) Box(Modifier.clickable(onClick = onOpenAlbum)) { SmallLine(it) } else SmallLine(it) }
                    artists?.let { SmallLine(it) }
                    if (result.roles.isNotEmpty()) SmallLine(result.roles.joinToString(", "), MaterialTheme.colorScheme.tertiary)
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
