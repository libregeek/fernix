// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A role (`COMPOSER`, `PERFORMER:GUITAR`, ...) as a small pill: transparent, with a thin border and a
 * reduced font, 11 sp (spec/architecture.md §9.7, person details).
 */
@Composable
fun RolePill(text: String, modifier: Modifier = Modifier, selected: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = if (selected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
