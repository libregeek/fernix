// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.os.Bundle

/** What the equalizer UI shows: the device's bands and presets plus the current settings. */
data class EqualizerState(
    val enabled: Boolean,
    /** Lowest and highest band level the device accepts, in millibels. */
    val minLevel: Int,
    val maxLevel: Int,
    /** Centre frequency of each band, in Hz. */
    val centerFrequenciesHz: List<Int>,
    /** Current level of each band, in millibels. */
    val levels: List<Int>,
    val presets: List<String>,
    /** Index into [presets] of the last preset applied, or -1 after a manual change. */
    val currentPreset: Int,
) {
    val bandCount: Int get() = levels.size

    fun toBundle() = Bundle().apply {
        putBoolean(ENABLED, enabled)
        putInt(MIN, minLevel)
        putInt(MAX, maxLevel)
        putIntArray(FREQS, centerFrequenciesHz.toIntArray())
        putIntArray(LEVELS, levels.toIntArray())
        putStringArray(PRESETS, presets.toTypedArray())
        putInt(PRESET, currentPreset)
    }

    companion object {
        private const val ENABLED = "enabled"
        private const val MIN = "min"
        private const val MAX = "max"
        private const val FREQS = "freqs"
        private const val LEVELS = "levels"
        private const val PRESETS = "presets"
        private const val PRESET = "preset"

        fun fromBundle(b: Bundle) = EqualizerState(
            enabled = b.getBoolean(ENABLED),
            minLevel = b.getInt(MIN),
            maxLevel = b.getInt(MAX),
            centerFrequenciesHz = b.getIntArray(FREQS)?.toList().orEmpty(),
            levels = b.getIntArray(LEVELS)?.toList().orEmpty(),
            presets = b.getStringArray(PRESETS)?.toList().orEmpty(),
            currentPreset = b.getInt(PRESET, -1),
        )
    }
}

/** Session commands between [PlaybackController] and the service that owns the equalizer. */
object EqualizerCommands {
    const val GET = "io.github.libregeek.fernix.eq.GET"
    const val SET_ENABLED = "io.github.libregeek.fernix.eq.SET_ENABLED"
    const val SET_BAND = "io.github.libregeek.fernix.eq.SET_BAND"
    const val USE_PRESET = "io.github.libregeek.fernix.eq.USE_PRESET"
    const val RESET = "io.github.libregeek.fernix.eq.RESET"

    val ALL = listOf(GET, SET_ENABLED, SET_BAND, USE_PRESET, RESET)

    const val ARG_ENABLED = "enabled"
    const val ARG_BAND = "band"
    const val ARG_LEVEL = "level"
    const val ARG_PRESET = "preset"
}
