// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.TrackEntity
import io.github.libregeek.fernix.playback.PlaybackUiState
import io.github.libregeek.fernix.tags.Credits
import io.github.libregeek.fernix.ui.common.Thumbnail
import io.github.libregeek.fernix.ui.common.trackThumbnail
import io.github.libregeek.fernix.ui.tracks.trackAlbumLine

/**
 * Persistent bar above the bottom navigation (spec/architecture.md §9). Shown while the queue is
 * not empty; tapping it opens Now Playing. Three lines, as in the Tracks list: the title; the album with
 * its date in brackets; the artists, both smaller. [track] is the playing track's database row; until
 * it arrives the title and artist from the player are shown.
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    track: TrackEntity?,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    if (state.queueSize == 0) return
    val title = track?.title?.takeIf { it.isNotEmpty() } ?: state.title
    val album = track?.let { trackAlbumLine(it.album, it.date) }
    val artists = track?.let { Credits.line(Credits.people(it.composer, it.lyricist, it.artist)) } ?: state.artist.takeIf { it.isNotEmpty() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The playing track's art, or the app logo when it has none, as in the track rows.
            if (track != null) {
                Thumbnail(trackThumbnail(track.albumId, track.mediaStoreId))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                album?.let { SmallLine(it) }
                artists?.let { SmallLine(it) }
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    painter = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                )
            }
            IconButton(onClick = onNext) {
                Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next))
            }
        }
    }
}

@Composable
private fun SmallLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
