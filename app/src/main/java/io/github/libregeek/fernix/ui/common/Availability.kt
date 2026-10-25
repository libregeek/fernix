// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.TrackRow

/** The ids of the folders on volumes that are not mounted (spec/architecture.md §6 *Unmounted volumes*). */
val LocalUnavailableFolders = compositionLocalOf<Set<Long>> { emptySet() }

/** True when the track's volume is not mounted: its row is dimmed and marked, and it cannot be played. */
@Composable
fun TrackRow.isUnavailable(): Boolean = folderId in LocalUnavailableFolders.current

/** A row of a track that is out of reach is half as strong as the others. */
fun Modifier.dimmedIf(unavailable: Boolean): Modifier = if (unavailable) alpha(0.5f) else this

/** The line above the title of a track that is out of reach, for a `ListItem`'s `overlineContent`. */
fun unavailableOverline(unavailable: Boolean): (@Composable () -> Unit)? =
    if (unavailable) {
        {
            Text(
                stringResource(R.string.track_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        null
    }
