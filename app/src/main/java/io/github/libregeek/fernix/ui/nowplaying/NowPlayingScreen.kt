// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.nowplaying

import android.content.res.Configuration
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import io.github.libregeek.fernix.playback.PlaybackUiState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // The whole screen fits without scrolling: the art takes what the other rows leave (and sits beside them
    // in landscape), on a soft wash of the main colour.
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.background))),
    ) {
        val header: @Composable () -> Unit = {
            NowPlayingHeader(
                onBack = onBack,
                onEqualizer = { showEqualizer = true },
                onDetails = { track?.let { onOpenTrack(it.id) } },
                onQueue = onOpenQueue,
                detailsEnabled = track != null,
            )
        }
        if (track == null) {
            Column {
                header()
                Text(
                    stringResource(R.string.nothing_playing),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Box
        }
        val art: @Composable (Modifier) -> Unit = { m ->
            Box(m, contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_album),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
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
            }
        }
        val info: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        track.title.ifEmpty { player.title.ifEmpty { "—" } },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // One tap adds the track to Favourites, another removes it (spec §9.1).
                    IconButton(onClick = vm::toggleFavourite, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painterResource(if (state.favourite) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
                            contentDescription = stringResource(
                                if (state.favourite) R.string.favourite_remove else R.string.favourite_add,
                            ),
                            tint = if (state.favourite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // The album name opens the album (spec §9, Album details); its date follows in brackets.
                AlbumLine(
                    track.album.takeIf { it.isNotEmpty() }?.let { io.github.libregeek.fernix.playback.MediaItems.display(it) },
                    track.date.takeIf { it.isNotEmpty() }?.let { io.github.libregeek.fernix.playback.MediaItems.display(it) },
                    onClick = track.albumId?.let { id -> { onOpenAlbum(id) } },
                )
                state.lines.forEach { line -> TagLineRow(line, onOpenPerson, onOpenTag) }
            }
        }
        val controls: @Composable () -> Unit = {
            SeekBar(playback, player.durationMs, player.isPlaying)
            PlaybackControls(playback, player)
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                art(Modifier.fillMaxHeight().weight(0.8f).padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
                Column(Modifier.weight(1.2f).fillMaxHeight()) {
                    header()
                    // A short landscape screen gives the details what is left; the controls always keep their size.
                    Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { info() }
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                art(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
                info()
                controls()
            }
        }
    }
}

/** Back on the left; the equalizer, the track's details and the queue on the right. */
@Composable
private fun NowPlayingHeader(onBack: () -> Unit, onEqualizer: () -> Unit, onDetails: () -> Unit, onQueue: () -> Unit, detailsEnabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.album_back))
        }
        Row {
            IconButton(onClick = onEqualizer, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_equalizer), stringResource(R.string.equalizer))
            }
            IconButton(onClick = onDetails, enabled = detailsEnabled, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_info), stringResource(R.string.track_details))
            }
            IconButton(onClick = onQueue, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_queue_music), stringResource(R.string.queue))
            }
        }
    }
}

/** Shuffle, previous, play/pause, next and repeat: small buttons, the play button a little larger. */
@Composable
private fun PlaybackControls(playback: PlaybackController, player: PlaybackUiState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { playback.setShuffle(!player.shuffle) }, modifier = Modifier.size(40.dp)) {
            Icon(
                painterResource(R.drawable.ic_shuffle),
                stringResource(R.string.shuffle),
                modifier = Modifier.size(20.dp),
                tint = if (player.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = playback::previous, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous), Modifier.size(24.dp))
        }
        FilledIconButton(onClick = playback::playPause, modifier = Modifier.size(52.dp)) {
            Icon(
                painterResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                stringResource(if (player.isPlaying) R.string.pause else R.string.play),
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = playback::next, modifier = Modifier.size(40.dp)) {
            Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next), Modifier.size(24.dp))
        }
        IconButton(onClick = { playback.setRepeatMode(nextRepeatMode(player.repeatMode)) }, modifier = Modifier.size(40.dp)) {
            Icon(
                painterResource(if (player.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                stringResource(
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> R.string.repeat_one
                        Player.REPEAT_MODE_ALL -> R.string.repeat_all
                        else -> R.string.repeat_off
                    },
                ),
                modifier = Modifier.size(20.dp),
                tint = if (player.repeatMode != Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun nextRepeatMode(mode: Int) = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

/** The album (opens it when tappable) with its date in brackets, on one line. */
@Composable
private fun AlbumLine(album: String?, date: String?, onClick: (() -> Unit)?) {
    if (album == null && date == null) return
    Row {
        if (album != null) {
            Text(
                album,
                modifier = Modifier.weight(1f, fill = false).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
                style = MaterialTheme.typography.bodyMedium,
                color = if (onClick != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (date != null) {
            Text(
                if (album != null) " ($date)" else date,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A slot on one line: its label, then its values side by side (wrapping if there are many); each value is tappable. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagLineRow(line: TagLine, onOpenPerson: (Long) -> Unit, onOpenTag: (keyId: Long, valueId: Long) -> Unit) {
    FlowRow(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        line.values.forEach { value ->
            Text(
                value.text,
                modifier = Modifier.clickable { if (value.isPerson) onOpenPerson(value.valueId) else onOpenTag(value.keyId, value.valueId) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.tertiary,
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
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        Slider(
            value = position.coerceIn(0f, durationMs.coerceAtLeast(1).toFloat()),
            onValueChange = { dragging = true; position = it },
            onValueChangeFinished = { playback.seekTo(position.toLong()); dragging = false },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            modifier = Modifier.height(32.dp),
            colors = SliderDefaults.colors(
                inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            ),
        )
        }
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
