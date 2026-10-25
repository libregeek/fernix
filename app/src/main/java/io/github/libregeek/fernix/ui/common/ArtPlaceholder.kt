// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.libregeek.fernix.R

/**
 * What stands in for album art that is loading or that a track doesn't have (spec/architecture.md §7
 * *Thumbnails*): the monochrome logo with the app's name under it in lower case as a faint watermark. The name is left
 * out of a small picture (a list thumbnail), where it would be too small to read.
 */
@Composable
fun ArtPlaceholder(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        // The embossed fern of the app icon, so the placeholder is the icon without its notes' shadow.
        Image(
            painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Icon(
            painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = Color.White,
        )
        if (side >= WATERMARK_MIN_SIDE) {
            Text(
                stringResource(R.string.app_name).lowercase(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = side * 0.1f).alpha(0.55f),
                color = Color.White,
                fontSize = (side.value * 0.1f).coerceAtMost(28f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (side.value * 0.02f).sp,
                maxLines = 1,
            )
        }
    }
}

private val WATERMARK_MIN_SIDE = 96.dp
