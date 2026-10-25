// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.tracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.data.db.SortField
import io.github.libregeek.fernix.data.db.SortLevel
import io.github.libregeek.fernix.data.db.TrackSort

@Composable
fun SortField.label(): String = stringResource(
    when (this) {
        SortField.TITLE -> R.string.sort_field_title
        SortField.ALBUM -> R.string.sort_field_album
        SortField.YEAR -> R.string.sort_field_year
        SortField.TRACK -> R.string.sort_field_track
        SortField.DURATION -> R.string.sort_field_duration
        SortField.DATE_ADDED -> R.string.sort_field_date_added
    },
)

/** `Year ↑ · Album ↑ · Track ↓`, for the summary under the actions. */
@Composable
fun TrackSort.summary(): String {
    val labels = levels.map { it.field.label() }
    return levels.indices.joinToString(" · ") { "${labels[it]} ${if (levels[it].ascending) "↑" else "↓"}" }
}

/**
 * The sort sheet (spec/architecture.md §9.3): up to four levels, each a field and a direction, with a
 * button to move a level up and one to remove it; presets fill the levels. Nothing changes the list
 * until **Apply**; the sheet works on its own copy, which survives rotation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SortSheet(current: TrackSort, onApply: (TrackSort) -> Unit, onDismiss: () -> Unit) {
    var encoded by rememberSaveable { mutableStateOf(current.encode()) }
    val levels = TrackSort.decode(encoded).levels
    fun set(new: List<SortLevel>) {
        encoded = TrackSort(new).encode()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.sort_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Preset(R.string.sort_preset_title) { encoded = TrackSort.DEFAULT.encode() }
                Preset(R.string.sort_preset_year_album_track) { encoded = TrackSort.YEAR_ALBUM_TRACK.encode() }
                Preset(R.string.sort_preset_album_year_track) { encoded = TrackSort.ALBUM_YEAR_TRACK.encode() }
                Preset(R.string.sort_preset_recent) { encoded = TrackSort.RECENTLY_ADDED.encode() }
            }
            levels.forEachIndexed { i, level ->
                LevelRow(
                    number = i + 1,
                    level = level,
                    taken = levels.map { it.field }.toSet() - level.field,
                    onField = { f -> set(levels.toMutableList().also { it[i] = level.copy(field = f) }) },
                    onFlip = { set(levels.toMutableList().also { it[i] = level.copy(ascending = !level.ascending) }) },
                    onUp = if (i == 0) null else { { set(levels.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) } },
                    onRemove = { set(levels.filterIndexed { j, _ -> j != i }) },
                )
            }
            if (levels.size < TrackSort.MAX_LEVELS) {
                val free = SortField.entries.first { f -> levels.none { it.field == f } }
                TextButton(onClick = { set(levels + SortLevel(free)) }) { Text(stringResource(R.string.sort_add_level)) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { encoded = TrackSort.DEFAULT.encode() }) { Text(stringResource(R.string.sort_reset)) }
                Button(onClick = { onApply(TrackSort.decode(encoded)) }) { Text(stringResource(R.string.sort_apply)) }
            }
        }
    }
}

@Composable
private fun Preset(label: Int, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(stringResource(label)) })
}

@Composable
private fun LevelRow(
    number: Int,
    level: SortLevel,
    taken: Set<SortField>,
    onField: (SortField) -> Unit,
    onFlip: () -> Unit,
    onUp: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onUp != null) {
            IconButton(onClick = onUp) { Icon(painterResource(R.drawable.ic_keyboard_arrow_up), stringResource(R.string.sort_move_up, number)) }
        } else {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("1", style = MaterialTheme.typography.titleMedium) }
        }
        TextButton(onClick = { menu = true }, modifier = Modifier.weight(1f)) {
            Text(level.field.label(), maxLines = 1)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SortField.entries.filter { it !in taken }.forEach { f ->
                    DropdownMenuItem(text = { Text(f.label()) }, onClick = { menu = false; onField(f) })
                }
            }
        }
        IconButton(onClick = onFlip) {
            Icon(
                painterResource(if (level.ascending) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward),
                stringResource(R.string.sort_change_direction, number) + ": " +
                    stringResource(if (level.ascending) R.string.sort_ascending else R.string.sort_descending),
            )
        }
        IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.sort_remove, number)) }
    }
}
