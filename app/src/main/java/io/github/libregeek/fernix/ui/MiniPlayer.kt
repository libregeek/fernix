// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.github.libregeek.fernix.playback.PlaybackUiState

/**
 * Persistent bar above the bottom navigation (spec/architecture.md §9). Shown while the queue is
 * not empty; tapping it opens Now Playing.
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    if (state.queueSize == 0) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.artist.isNotEmpty()) {
                    Text(
                        state.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
