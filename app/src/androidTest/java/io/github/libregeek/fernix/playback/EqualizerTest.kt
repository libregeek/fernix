// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.FernixApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The equalizer through the real service. Skipped on a device without the effect. */
@RunWith(AndroidJUnit4::class)
class EqualizerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings get() = (context as FernixApp).container.settings
    private lateinit var controller: PlaybackController

    @Before
    fun setUp() {
        settings.equalizerEnabled = false
        settings.equalizerLevels = null
        controller = onMain { PlaybackController(context) }
    }

    @After
    fun tearDown() {
        onMain { controller.resetEqualizer(); controller.setEqualizerEnabled(false); controller.release() }
        settings.equalizerEnabled = false
        settings.equalizerLevels = null
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun awaitState(what: String, condition: (EqualizerState) -> Boolean): EqualizerState {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            controller.equalizer.value?.let { if (condition(it)) return it }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what; state=${controller.equalizer.value}")
    }

    private fun loadOrSkip(): EqualizerState {
        onMain { controller.loadEqualizer() }
        val end = System.currentTimeMillis() + 5_000
        while (controller.equalizer.value == null && System.currentTimeMillis() < end) Thread.sleep(50)
        assumeTrue("Device has no equalizer effect", controller.equalizer.value != null)
        return controller.equalizer.value!!
    }

    @Test
    fun describesTheDevicesBandsAndPresets() {
        val s = loadOrSkip()
        assertTrue(s.bandCount > 0)
        assertEquals(s.bandCount, s.centerFrequenciesHz.size)
        assertTrue(s.minLevel < 0 && s.maxLevel > 0)
        assertTrue(s.centerFrequenciesHz.zipWithNext().all { (a, b) -> a < b })
        assertEquals(false, s.enabled)
        assertEquals(-1, s.currentPreset)
    }

    @Test
    fun enableAndBandChanges_applyAndArePersisted() {
        val s = loadOrSkip()

        onMain { controller.setEqualizerEnabled(true) }
        awaitState("enabled") { it.enabled }

        val target = s.maxLevel
        onMain { controller.setEqualizerBand(0, target) }
        val after = awaitState("band 0 raised") { it.levels[0] == target }
        assertEquals(-1, after.currentPreset)

        // Out-of-range levels are clamped, out-of-range bands ignored.
        onMain { controller.setEqualizerBand(0, s.maxLevel + 5000) }
        awaitState("clamped") { it.levels[0] == s.maxLevel }
        onMain { controller.setEqualizerBand(99, 100) }
        onMain { controller.loadEqualizer() }

        assertTrue(settings.equalizerEnabled)
        assertEquals(s.maxLevel, settings.equalizerLevels!![0])
    }

    @Test
    fun presetThenReset() {
        val s = loadOrSkip()
        assumeTrue("Device has no presets", s.presets.isNotEmpty())

        onMain { controller.useEqualizerPreset(0) }
        val applied = awaitState("preset 0") { it.currentPreset == 0 }
        assertNotNull(applied.presets[0])

        onMain { controller.resetEqualizer() }
        val flat = awaitState("reset") { it.currentPreset == -1 && it.levels.all { l -> l == 0 } }
        assertEquals(s.bandCount, flat.levels.size)
    }
}
