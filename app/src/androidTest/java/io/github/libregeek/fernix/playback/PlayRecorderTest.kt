// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.libregeek.fernix.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Plays the short sample files with a real ExoPlayer and checks which tracks are reported as played. */
@RunWith(AndroidJUnit4::class)
class PlayRecorderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val files = listOf("sample.wav", "sample.opus").map { File(context.cacheDir, "rec-$it") }
    private val played = Collections.synchronizedList(mutableListOf<Long>())
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        listOf("sample.wav", "sample.opus").zip(files).forEach { (asset, file) ->
            instrumentation.context.assets.open(asset).use { i -> file.outputStream().use { i.copyTo(it) } }
        }
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build()
            PlayRecorder { played += it }.attach(player)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player.release() }
        files.forEach { it.delete() }
    }

    private fun item(id: Long, file: File) =
        MediaItem.Builder().setMediaId(MediaItems.mediaId(id)).setUri(Uri.fromFile(file)).build()

    private fun onMain(block: ExoPlayer.() -> Unit) = instrumentation.runOnMainSync { player.block() }

    private fun <T> read(block: ExoPlayer.() -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = player.block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out; played=$played")
    }

    @Test
    fun playingAQueueThrough_countsEachTrackOnce_includingTheLastOne() {
        onMain {
            setMediaItems(listOf(item(1, files[0]), item(2, files[1])))
            prepare()
            play()
        }
        waitFor { read { playbackState } == Player.STATE_ENDED }
        assertEquals(listOf(1L, 2L), played.toList())
    }

    @Test
    fun skippingBeforeTheEnd_doesNotCount() {
        onMain {
            setMediaItems(listOf(item(1, files[0]), item(2, files[1])))
            prepare()
            play()
        }
        waitFor { read { currentPosition } > 100 }
        onMain { seekToNextMediaItem() }
        waitFor { read { playbackState } == Player.STATE_ENDED }
        assertEquals(listOf(2L), played.toList())
    }

    @Test
    fun seekingToTheEnd_countsTheTrackOnce() {
        onMain {
            setMediaItems(listOf(item(1, files[0]), item(2, files[1])))
            prepare()
            play()
        }
        waitFor { read { currentPosition } > 100 }
        // Like holding fast-forward: the seek lands on the very end of the first item.
        onMain { seekTo(currentMediaItemIndex, duration) }
        waitFor { read { playbackState } == Player.STATE_ENDED }
        assertEquals(listOf(1L, 2L), played.toList())
    }

    @Test
    fun replacingTheQueueOrStopping_doesNotCount() {
        onMain {
            setMediaItems(listOf(item(1, files[0])))
            prepare()
            play()
        }
        waitFor { read { currentPosition } > 100 }
        onMain {
            setMediaItems(listOf(item(2, files[1])))
            prepare()
        }
        Thread.sleep(300)
        onMain { stop() }
        Thread.sleep(300)
        assertEquals(emptyList<Long>(), played.toList())
    }

    @Test
    fun repeatOne_countsEveryPass() {
        onMain {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItems(listOf(item(5, files[0])))
            prepare()
            play()
        }
        waitFor { played.size >= 2 }
        onMain { stop() }
        assertTrue(played.all { it == 5L })
    }
}
