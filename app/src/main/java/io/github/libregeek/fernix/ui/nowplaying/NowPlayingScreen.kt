// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.media3.common.Player
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.Settings
import io.github.libregeek.fernix.data.db.FernixDatabase
import io.github.libregeek.fernix.playback.PlaybackController
import coil3.compose.AsyncImage
import io.github.libregeek.fernix.ui.art.TrackArt
import io.github.libregeek.fernix.ui.equalizer.EqualizerSheet
import kotlinx.coroutines.delay

/** Now Playing (spec/architecture.md §9.1). Album art waits for Coil, so a placeholder stands in. */
@Composable
fun NowPlayingScreen(
    db: FernixDatabase,
    settings: Settings,
    playback: PlaybackController,
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTrack: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenTag: (keyId: Long, valueId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: NowPlayingViewModel = viewModel(
        factory = viewModelFactory { initializer { NowPlayingViewModel(db, settings, playback) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reloadSlots() }
    val player by playback.state.collectAsStateWithLifecycle()
    val track = state.track
    var showEqualizer by rememberSaveable { mutableStateOf(false) }
    if (showEqualizer) EqualizerSheet(playback, onDismiss = { showEqualizer = false })

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
            }
            Row {
                IconButton(onClick = { showEqualizer = true }) {
                    Icon(painterResource(R.drawable.ic_equalizer), stringResource(R.string.equalizer))
                }
                IconButton(onClick = { onOpenTrack(track?.id ?: return@IconButton) }, enabled = track != null) {
                    Icon(painterResource(R.drawable.ic_info), stringResource(R.string.track_details))
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(painterResource(R.drawable.ic_queue_music), stringResource(R.string.queue))
                }
            }
        }
        if (track == null) {
            Text(
                stringResource(R.string.nothing_playing),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }

        Box(
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_album),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Over the placeholder: nothing is drawn while loading or when the file has no picture.
            AsyncImage(
                model = TrackArt(track.mediaStoreId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    track.title.ifEmpty { player.title.ifEmpty { "—" } },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // One tap adds the track to Favourites, another removes it (spec §9.1).
                IconButton(onClick = vm::toggleFavourite) {
                    Icon(
                        painterResource(if (state.favourite) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                        contentDescription = stringResource(
                            if (state.favourite) R.string.favourite_remove else R.string.favourite_add,
                        ),
                        tint = if (state.favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The album name opens the album (spec §9, Album details).
            Detail(
                track.album.takeIf { it.isNotEmpty() }?.let { io.github.libregeek.fernix.playback.MediaItems.display(it) },
                onClick = track.albumId?.let { id -> { onOpenAlbum(id) } },
            )
            Detail(track.date.takeIf { it.isNotEmpty() }?.let { io.github.libregeek.fernix.playback.MediaItems.display(it) })

            state.lines.forEach { line -> TagLineRow(line, onOpenPerson, onOpenTag) }
        }

        SeekBar(playback, player.durationMs, player.isPlaying)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { playback.setShuffle(!player.shuffle) }) {
                Icon(
                    painterResource(R.drawable.ic_shuffle),
                    stringResource(R.string.shuffle),
                    tint = if (player.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = playback::previous) {
                Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous))
            }
            FilledIconButton(onClick = playback::playPause, modifier = Modifier.size(64.dp)) {
                Icon(
                    painterResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    stringResource(if (player.isPlaying) R.string.pause else R.string.play),
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = playback::next) {
                Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next))
            }
            IconButton(onClick = { playback.setRepeatMode(nextRepeatMode(player.repeatMode)) }) {
                Icon(
                    painterResource(if (player.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                    stringResource(
                        when (player.repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.string.repeat_one
                            Player.REPEAT_MODE_ALL -> R.string.repeat_all
                            else -> R.string.repeat_off
                        },
                    ),
                    tint = if (player.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun nextRepeatMode(mode: Int) = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

@Composable
private fun Detail(text: String?, onClick: (() -> Unit)? = null) {
    if (text == null) return
    Text(
        text,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A slot: its label, then each value on its own line; a person's name opens Tracks filtered by them, any other value by it. */
@Composable
private fun TagLineRow(line: TagLine, onOpenPerson: (Long) -> Unit, onOpenTag: (keyId: Long, valueId: Long) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(line.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        line.values.forEach { value ->
            Text(
                value.text,
                modifier = Modifier.clickable { if (value.isPerson) onOpenPerson(value.valueId) else onOpenTag(value.keyId, value.valueId) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Polls the position while playing; dragging shows the dragged value and seeks on release. */
@Composable
private fun SeekBar(playback: PlaybackController, durationMs: Long, isPlaying: Boolean) {
    var position by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying, durationMs) {
        while (true) {
            if (!dragging) position = playback.currentPositionMs().toFloat()
            if (!isPlaying) break
            delay(POLL_MS)
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Slider(
            value = position.coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = { dragging = true; position = it },
            onValueChangeFinished = { playback.seekTo(position.toLong()); dragging = false },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(position.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(clock(durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private const val POLL_MS = 500L
