// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.ui.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libregeek.fernix.R
import io.github.libregeek.fernix.playback.EqualizerState
import io.github.libregeek.fernix.playback.PlaybackController

/**
 * The equalizer in a bottom sheet over Now Playing (spec/architecture.md §9.1). The engine lives in
 * the playback service; this only shows its state and sends the user's changes. It is off by default
 * and while off adds nothing to the signal (§8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(playback: PlaybackController, onDismiss: () -> Unit) {
    val state by playback.equalizer.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { playback.loadEqualizer() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.equalizer), style = MaterialTheme.typography.titleLarge)
            val eq = state
            if (eq == null) {
                Text(
                    stringResource(R.string.equalizer_unavailable),
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                EqualizerControls(eq, playback)
            }
        }
    }
}

@Composable
private fun EqualizerControls(eq: EqualizerState, playback: PlaybackController) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.equalizer_on), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = eq.enabled, onCheckedChange = playback::setEqualizerEnabled)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresetPicker(eq, playback)
        TextButton(onClick = playback::resetEqualizer) { Text(stringResource(R.string.equalizer_reset)) }
    }

    eq.levels.forEachIndexed { band, level ->
        Band(
            label = frequency(eq.centerFrequenciesHz.getOrNull(band) ?: 0),
            level = level,
            min = eq.minLevel,
            max = eq.maxLevel,
            enabled = eq.enabled,
            onChanged = { playback.setEqualizerBand(band, it) },
        )
    }
}

@Composable
private fun PresetPicker(eq: EqualizerState, playback: PlaybackController) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = eq.presets.isNotEmpty()) {
            Text(
                stringResource(R.string.equalizer_preset) + ": " +
                    (eq.presets.getOrNull(eq.currentPreset) ?: stringResource(R.string.equalizer_custom)),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            eq.presets.forEachIndexed { index, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; playback.useEqualizerPreset(index) })
            }
        }
    }
}

/** One band: while dragging the slider shows the dragged value, and the level is sent on release. */
@Composable
private fun Band(label: String, level: Int, min: Int, max: Int, enabled: Boolean, onChanged: (Int) -> Unit) {
    var shown by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = shown.coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { shown = it },
            onValueChangeFinished = { onChanged(shown.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%+.1f dB".format(shown / 100f),
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun frequency(hz: Int): String = when {
    hz < 1000 -> "$hz Hz"
    hz % 1000 == 0 -> "${hz / 1000} kHz"
    else -> "%.1f kHz".format(hz / 1000f)
}
