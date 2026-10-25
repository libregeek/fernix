// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.ui.art.AlbumThumbnail
import io.github.libregeek.fernix.ui.art.TrackThumbnail

/**
 * The art thumbnail of a list row (spec/architecture.md §7 *Thumbnails*): the album placeholder until the
 * art arrives, and for good when there is none. [model] is an [AlbumThumbnail] or a [TrackThumbnail].
 */
@Composable
fun Thumbnail(model: Any, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/** The thumbnail model of a track row: its album's art, or its own picture when it has no album. */
fun trackThumbnail(albumId: Long?, mediaStoreId: Long): Any =
    if (albumId != null) AlbumThumbnail(albumId) else TrackThumbnail(mediaStoreId)
