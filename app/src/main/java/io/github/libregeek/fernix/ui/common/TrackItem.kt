// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import io.github.libregeek.fernix.data.db.TrackRow
import io.github.libregeek.fernix.playback.MediaItems
import io.github.libregeek.fernix.ui.tracks.trackAlbumLine

/**
 * A track row (spec/architecture.md §9.3), the same on the Tracks list and in the Queue: the art, the title; below
 * it the album with its date in brackets, smaller; below that the artists, smaller. [roles] (a person's roles on
 * the track) follow when given. [modifier] carries the click; [trailingContent] is the row's menu or buttons.
 */
@Composable
fun TrackListItem(
    track: TrackRow,
    modifier: Modifier = Modifier,
    roles: List<String> = emptyList(),
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val album = trackAlbumLine(track.album, track.date)
    val artists = MediaItems.display(track.artist)
    val gone = track.isUnavailable()
    ListItem(
        modifier = Modifier.dimmedIf(gone).then(modifier),
        overlineContent = unavailableOverline(gone),
        leadingContent = { Thumbnail(trackThumbnail(track.albumId, track.mediaStoreId)) },
        headlineContent = { Text(track.title.ifEmpty { "\u2014" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (album == null && artists == null && roles.isEmpty()) {
            null
        } else {
            {
                Column {
                    album?.let { SmallLine(it) }
                    artists?.let { SmallLine(it) }
                    if (roles.isNotEmpty()) SmallLine(roles.joinToString(", "), MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        trailingContent = trailingContent,
    )
}

@Composable
private fun SmallLine(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
