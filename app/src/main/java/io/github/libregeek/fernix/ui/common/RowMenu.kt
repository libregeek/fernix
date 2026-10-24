// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.libregeek.fernix.R

/** The overflow menu on a list row: Play and Add to queue. */
@Composable
fun RowMenu(onPlay: () -> Unit, onEnqueue: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, onClick = { open = false; onPlay() })
            DropdownMenuItem(text = { Text(stringResource(R.string.add_to_queue)) }, onClick = { open = false; onEnqueue() })
        }
    }
}
