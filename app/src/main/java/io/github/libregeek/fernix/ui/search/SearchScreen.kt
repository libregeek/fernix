// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.search

import io.github.libregeek.fernix.data.db.TrackRow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import io.github.libregeek.fernix.ui.common.AddToPlaylistDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.libregeek.fernix.data.db.TrackResult
import io.github.libregeek.fernix.data.db.TrackSearch
import io.github.libregeek.fernix.playback.MediaItems
import io.github.libregeek.fernix.playback.PlaybackController
import io.github.libregeek.fernix.ui.common.RowMenu
import io.github.libregeek.fernix.ui.common.ListMenu
import io.github.libregeek.fernix.ui.split.SplitTagsDialog

@Composable
fun SearchScreen(
    db: FernixDatabase,
    search: TrackSearch,
    playback: PlaybackController,
    personId: Long = 0,
    onOpenTrack: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SearchViewModel = viewModel(
        key = "search$personId",
        factory = viewModelFactory { initializer { SearchViewModel(db, search, playback, personId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var splitting by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var addTracks by remember { mutableStateOf<(suspend () -> List<TrackRow>)?>(null) }

    LaunchedEffect(listState, state.results.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= state.results.size - LOAD_AHEAD) vm.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.text,
            onValueChange = vm::setText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.person?.let { person ->
                InputChip(
                    selected = true,
                    onClick = { vm.setPerson(null) },
                    label = { Text(person.name) },
                    trailingIcon = { Text("✕", modifier = Modifier.padding(start = 4.dp)) },
                )
            }
            state.chips.forEach { chip ->
                InputChip(
                    selected = true,
                    onClick = { vm.removeChip(chip) },
                    label = { Text("${chip.key}: ${chip.value}") },
                    trailingIcon = { Text("✕", modifier = Modifier.padding(start = 4.dp)) },
                )
            }
            AssistChip(onClick = vm::openKeyPicker, label = { Text(stringResource(R.string.search_add_filter)) })
        }
        if (state.results.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::playAll) { Text(stringResource(R.string.play)) }
                OutlinedButton(onClick = vm::enqueueAll) { Text(stringResource(R.string.add_to_queue)) }
                ListMenu(onSplitTags = { splitting = true })
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (state.noResults) {
                Text(
                    stringResource(R.string.search_no_results),
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
    addTracks?.let { AddToPlaylistDialog(db, it) { addTracks = null } }
    if (splitting) SplitTagsDialog(db, trackIds = vm::trackIds) { splitting = false }
}

@Composable
private fun ResultRow(
    result: TrackResult,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDetails: () -> Unit,
) {
    val track = result.track
    // With a person filter the roles on the track lead; otherwise artist · album.
    val subtitle = if (result.roles.isNotEmpty()) {
        result.roles.joinToString(", ")
    } else {
        listOfNotNull(MediaItems.display(track.artist), MediaItems.display(track.album)).joinToString(" · ")
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        headlineContent = { Text(track.title.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle.ifEmpty { null }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { RowMenu(onPlay = onPlay, onEnqueue = onEnqueue, onAddToPlaylist = onAddToPlaylist, onDetails = onDetails) },
    )
}

private const val LOAD_AHEAD = 20
