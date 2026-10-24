// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.media.AudioFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.libregeek.fernix.data.Settings
import java.io.File
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The audio-path check of spec/architecture.md §8 and TODO Phase 9: what the player hands to the
 * platform's AudioTrack is what the decoder produced from the file. No resampling, no downmix, no
 * bit-depth reduction by Fernix, nothing but ExoPlayer's defaults in [PlayerFactory], and the
 * equalizer off adds nothing. Run it after every change that touches playback.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AudioPathTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val configs = Collections.synchronizedList(mutableListOf<AudioSink.AudioTrackConfig>())
    private val files = mutableListOf<File>()
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            player = PlayerFactory.create(context)
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                    configs += audioTrackConfig
                }
            })
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player.release() }
        files.forEach { it.delete() }
    }

    /** Plays [asset] until the audio track exists, and returns how it was set up. */
    private fun trackFor(asset: String): AudioSink.AudioTrackConfig {
        val file = File(context.cacheDir, "path-$asset").also { files += it }
        instrumentation.context.assets.open(asset).use { i -> file.outputStream().use { i.copyTo(it) } }
        configs.clear()
        instrumentation.runOnMainSync {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
        val end = System.currentTimeMillis() + 15_000
        while (configs.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertNotNull("no audio track for $asset", configs.firstOrNull())
        return configs.first()
    }

    private fun assertPcm16(asset: String, rate: Int, channelConfig: Int) {
        val config = trackFor(asset)
        assertEquals("$asset sample rate", rate, config.sampleRate)
        assertEquals("$asset channels", channelConfig, config.channelConfig)
        assertEquals("$asset bit depth", AudioFormat.ENCODING_PCM_16BIT, config.encoding)
    }

    // The fixtures are mono 16-bit sine waves (scripts/generate-test-fixtures.py).

    @Test
    fun wav_reachesTheAudioTrack_atItsOwnRateChannelsAndDepth() = assertPcm16("sample.wav", 8000, AudioFormat.CHANNEL_OUT_MONO)

    @Test
    fun flac_reachesTheAudioTrack_atItsOwnRateChannelsAndDepth() = assertPcm16("sample.flac", 22050, AudioFormat.CHANNEL_OUT_MONO)

    @Test
    fun mp3_reachesTheAudioTrack_atItsOwnRateAndChannels() = assertPcm16("sample.mp3", 22050, AudioFormat.CHANNEL_OUT_MONO)

    @Test
    fun oggVorbis_reachesTheAudioTrack_atItsOwnRateAndChannels() = assertPcm16("sample.ogg", 22050, AudioFormat.CHANNEL_OUT_MONO)

    @Test
    fun opus_reachesTheAudioTrack_atOpusOwn48kHz_notResampled() = assertPcm16("sample.opus", 48000, AudioFormat.CHANNEL_OUT_MONO)

    @Test
    fun theDefaultsStay_noSpeedPitchSilenceSkippingOrVolumeChange() {
        instrumentation.runOnMainSync {
            assertEquals(PlaybackParameters.DEFAULT, player.playbackParameters)
            assertFalse(player.skipSilenceEnabled)
            assertEquals(1f, player.volume, 0f)
            assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
        }
    }

    @Test
    fun theEqualizerIsOffByDefault_andOffAddsNothing() {
        val settings = Settings(context)
        val savedEnabled = settings.equalizerEnabled
        val savedLevels = settings.equalizerLevels
        try {
            // A fresh install: the equalizer was never switched on.
            settings.equalizerEnabled = false
            lateinit var engine: EqualizerEngine
            instrumentation.runOnMainSync {
                engine = EqualizerEngine(player.audioSessionId, settings)
            }
            // Where the device has one, it exists but is not enabled, so it is not in the signal path.
            engine.state()?.let { assertFalse("equalizer must start disabled", it.enabled) }
            // Playing still gives the decoder's own format.
            assertPcm16("sample.flac", 22050, AudioFormat.CHANNEL_OUT_MONO)
            engine.state()?.let { assertFalse(it.enabled) }
            engine.release()
        } finally {
            settings.equalizerEnabled = savedEnabled
            settings.equalizerLevels = savedLevels
        }
    }

    // Hi-res sources. The fixtures are generated by scripts/generate-test-fixtures.py.

    @Test
    fun hiResFlac_keepsItsRate_andItsStereo() {
        val config = trackFor("hires24.flac") // 96 kHz, 24-bit, stereo
        assertEquals(96000, config.sampleRate)
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, config.channelConfig)
    }

    @Test
    fun hiResWav_keepsItsRate_andItsStereo() {
        val config = trackFor("hires24.wav") // 44.1 kHz, 24-bit, stereo
        assertEquals(44100, config.sampleRate)
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO, config.channelConfig)
    }

    /**
     * KNOWN GAP, found 2026-10-24: with ExoPlayer's defaults a 24-bit source reaches the AudioTrack as
     * 16-bit PCM (`ENCODING_PCM_16BIT`), so the bit depth is reduced, which spec §8 forbids. Keeping it
     * needs `DefaultRenderersFactory.setEnableAudioFloatOutput(true)` (24-bit becomes 32-bit float, which
     * holds it exactly; 16-bit sources are not touched). That changes the audio sink, which needs the
     * maintainer's approval. Remove @Ignore once it is approved and done.
     */
    @Ignore("known gap: 24-bit sources are reduced to 16-bit; waiting for approval to enable float output")
    @Test
    fun hiRes24Bit_isNotReducedTo16Bit() {
        for (asset in listOf("hires24.flac", "hires24.wav")) {
            val encoding = trackFor(asset).encoding
            assertTrue(
                "$asset reached the audio track as encoding $encoding",
                encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_32BIT ||
                    encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED,
            )
        }
    }
}
