// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
 * The actions on a whole list of tracks, as one tonal capsule of icons: **Play**, **Add to queue**
 * and **Add to playlist**, and on the Tracks screen **Sort** (spec/architecture.md §9.3). Also used by the person details.
 */
@Composable
fun ListActions(
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    /** A fourth button, **Sort**, when given; [sortActive] fills it while the order is not the default. */
    onSort: (() -> Unit)? = null,
    /** A **Shuffle** button, before Sort, when given: replaces the queue with the list, shuffled. */
    onShuffle: (() -> Unit)? = null,
    sortActive: Boolean = false,
) {
    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(painterResource(R.drawable.ic_play), stringResource(R.string.play))
            }
            if (onShuffle != null) {
                IconButton(onClick = onShuffle) {
                    Icon(painterResource(R.drawable.ic_shuffle), stringResource(R.string.shuffle))
                }
            }
            IconButton(onClick = onEnqueue) {
                Icon(painterResource(R.drawable.ic_queue_add), stringResource(R.string.add_to_queue))
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(painterResource(R.drawable.ic_playlist_add), stringResource(R.string.add_to_playlist))
            }
            if (onSort != null) {
                IconButton(
                    onClick = onSort,
                    colors = if (sortActive) {
                        IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        )
                    } else {
                        IconButtonDefaults.iconButtonColors()
                    },
                ) {
                    Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.sort))
                }
            }
        }
    }
}
