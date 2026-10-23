// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.media.audiofx.Equalizer
import io.github.libregeek.fernix.data.Settings

/**
 * The platform equalizer attached to the player's audio session (spec/architecture.md §8).
 * Lives in the playback service. Changes are saved to [Settings] and restored on the next start.
 * [available] is false on devices without the effect; every other call is then a no-op.
 */
class EqualizerEngine(audioSessionId: Int, private val settings: Settings) {

    private val eq: Equalizer? = try {
        // Priority 0: the default, so another effect can take precedence.
        Equalizer(0, audioSessionId)
    } catch (e: Exception) {
        null
    }

    private var presetIndex = -1

    val available: Boolean get() = eq != null

    init {
        eq?.let { restore(it) }
    }

    private fun restore(eq: Equalizer) {
        val saved = settings.equalizerLevels
        if (saved != null) {
            val (min, max) = levelRange(eq)
            for (band in 0 until minOf(saved.size, eq.numberOfBands.toInt())) {
                eq.setBandLevel(band.toShort(), saved[band].coerceIn(min, max).toShort())
            }
        }
        eq.enabled = settings.equalizerEnabled
    }

    fun state(): EqualizerState? {
        val eq = eq ?: return null
        val (min, max) = levelRange(eq)
        val bands = eq.numberOfBands.toInt()
        return EqualizerState(
            enabled = eq.enabled,
            minLevel = min,
            maxLevel = max,
            centerFrequenciesHz = List(bands) { eq.getCenterFreq(it.toShort()) / 1000 },
            levels = List(bands) { eq.getBandLevel(it.toShort()).toInt() },
            presets = List(eq.numberOfPresets.toInt()) { eq.getPresetName(it.toShort()) },
            currentPreset = presetIndex,
        )
    }

    fun setEnabled(enabled: Boolean) {
        val eq = eq ?: return
        eq.enabled = enabled
        settings.equalizerEnabled = enabled
    }

    fun setBandLevel(band: Int, level: Int) {
        val eq = eq ?: return
        if (band !in 0 until eq.numberOfBands.toInt()) return
        val (min, max) = levelRange(eq)
        eq.setBandLevel(band.toShort(), level.coerceIn(min, max).toShort())
        presetIndex = -1
        save(eq)
    }

    fun usePreset(index: Int) {
        val eq = eq ?: return
        if (index !in 0 until eq.numberOfPresets.toInt()) return
        eq.usePreset(index.toShort())
        presetIndex = index
        save(eq)
    }

    /** All bands back to 0 dB. */
    fun reset() {
        val eq = eq ?: return
        for (band in 0 until eq.numberOfBands.toInt()) eq.setBandLevel(band.toShort(), 0)
        presetIndex = -1
        save(eq)
    }

    fun release() {
        eq?.release()
    }

    private fun save(eq: Equalizer) {
        settings.equalizerLevels = List(eq.numberOfBands.toInt()) { eq.getBandLevel(it.toShort()).toInt() }
    }

    private fun levelRange(eq: Equalizer): Pair<Int, Int> =
        eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
}
