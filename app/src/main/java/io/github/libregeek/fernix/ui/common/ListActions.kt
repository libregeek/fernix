// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R

/**
 * The three actions on a whole list of tracks, as one tonal capsule of icons: **Play**, **Add to queue**
 * and **Add to playlist** (spec/architecture.md §9.3). Used by the Tracks screen and the person details.
 */
@Composable
fun ListActions(onPlay: () -> Unit, onEnqueue: () -> Unit, onAddToPlaylist: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(painterResource(R.drawable.ic_play), stringResource(R.string.play))
            }
            IconButton(onClick = onEnqueue) {
                Icon(painterResource(R.drawable.ic_queue_add), stringResource(R.string.add_to_queue))
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(painterResource(R.drawable.ic_playlist_add), stringResource(R.string.add_to_playlist))
            }
        }
    }
}
